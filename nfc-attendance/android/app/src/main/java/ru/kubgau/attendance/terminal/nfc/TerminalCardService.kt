package ru.kubgau.attendance.terminal.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.data.AppDatabase

/**
 * Телефон преподавателя «притворяется» NFC-меткой формата NDEF Type 4 Tag.
 *
 * Обмен идёт по ISO 7816-4, как с настоящей меткой:
 *   1. считыватель выбирает приложение (SELECT AID = D2760000850101);
 *   2. читает файл описания возможностей CC (E103);
 *   3. читает файл NDEF (E104): 2 байта длины + само NDEF-сообщение;
 *   4. считыватель получает JSON с данными пары и закрывает соединение.
 *
 * Как только сообщение прочитано целиком, мы фиксируем «касание»: сохраняем его
 * в Room и отправляем на сервер. По этому касанию сервер подтверждает отметку
 * студента — то есть отметка, сделанная без прикладывания телефона, будет
 * помечена как неподтверждённая.
 */
class TerminalCardService : HostApduService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Что сейчас выбрано считывателем. */
    private enum class SelectedFile { NONE, CC, NDEF }

    private var selectedFile = SelectedFile.NONE

    /** Файл NDEF, собранный для текущего касания (обновляется на каждый SELECT). */
    private var ndefFile: ByteArray = ByteArray(0)
    private var ndefTimestamp: String = ""
    private var ndefSessionId: String? = null

    /** Прочитано ли сообщение целиком в текущем касании. */
    private var messageServed = false
    private var tapRecorded = false

    override fun onCreate() {
        super.onCreate()
        // подхватываем активную пару, даже если приложение только что перезапустилось
        scope.launch {
            AppDatabase.get(applicationContext).sessionDao().activeSession()?.let { session ->
                if (TerminalState.activeSession?.id != session.id) {
                    TerminalState.activeSession = session
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ APDU

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        if (apdu == null || apdu.size < 4) return SW_WRONG_LENGTH

        val instruction = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        return when (instruction) {
            INS_SELECT -> handleSelect(apdu, p1)
            INS_READ_BINARY -> handleReadBinary(apdu, p1, p2)
            INS_UPDATE_BINARY -> SW_CONDITIONS_NOT_SATISFIED   // метка только для чтения
            0xC0 -> SW_INS_NOT_SUPPORTED                        // GET RESPONSE не нужен
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    /** SELECT: либо по имени приложения (P1 = 04), либо по идентификатору файла. */
    private fun handleSelect(apdu: ByteArray, p1: Int): ByteArray {
        val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
        if (lc == 0 || apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)

        // эмуляция выключена — для считывателя метки «нет»
        if (!TerminalState.emulating) {
            selectedFile = SelectedFile.NONE
            return SW_FILE_NOT_FOUND
        }

        return if (p1 == 0x04) {
            // выбор приложения по AID
            if (data.contentEquals(AID_NDEF_TYPE4) || data.contentEquals(AID_VISITS11)) {
                prepareNdefFile()
                selectedFile = SelectedFile.CC
                SW_OK
            } else {
                SW_FILE_NOT_FOUND
            }
        } else {
            // выбор файла по идентификатору: E103 — CC, E104 — NDEF
            when {
                data.contentEquals(FILE_ID_CC) -> {
                    prepareNdefFile()
                    selectedFile = SelectedFile.CC
                    SW_OK
                }
                data.contentEquals(FILE_ID_NDEF) -> {
                    if (ndefFile.isEmpty()) prepareNdefFile()
                    selectedFile = SelectedFile.NDEF
                    SW_OK
                }
                else -> SW_FILE_NOT_FOUND
            }
        }
    }

    /** READ BINARY: отдаём кусок файла от offset длиной Le. */
    private fun handleReadBinary(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        if (selectedFile == SelectedFile.NONE) return SW_CONDITIONS_NOT_SATISFIED

        val offset = (p1 shl 8) or p2
        val le = when {
            apdu.size <= 4 -> 0
            apdu.size == 5 -> apdu[4].toInt() and 0xFF
            else -> apdu[apdu.size - 1].toInt() and 0xFF
        }
        val wanted = if (le == 0) 0x100 else le

        val content = if (selectedFile == SelectedFile.CC) buildCcFile() else ndefFile
        if (offset >= content.size) return SW_WRONG_PARAMETERS

        val length = minOf(wanted, content.size - offset)
        val chunk = content.copyOfRange(offset, offset + length)

        if (selectedFile == SelectedFile.NDEF && !messageServed && offset + length >= ndefFile.size) {
            onMessageServed()
        }

        return chunk + SW_OK
    }

    // -------------------------------------------------------------- механика

    /** Собирает NDEF-файл под текущую пару: свежий timestamp на каждое касание. */
    private fun prepareNdefFile() {
        val session = TerminalState.activeSession
        if (session == null) {
            ndefFile = ByteArray(0)
            ndefSessionId = null
            return
        }

        val timestamp = TerminalState.nowIso()
        val payload = NdefMessageBuilder.payloadJson(
            sessionId = session.id,
            subject = session.subject,
            teacherId = session.teacherId,
            timestamp = timestamp,
        )
        ndefFile = NdefMessageBuilder.buildNdefFile(payload)
        ndefTimestamp = timestamp
        ndefSessionId = session.id
        messageServed = false
        tapRecorded = false
    }

    /** Файл CC (Capability Container) — описывает NDEF-файл E104. */
    private fun buildCcFile(): ByteArray {
        val maxSize = ndefFile.size.coerceAtLeast(1).coerceAtMost(NdefMessageBuilder.MAX_NDEF_FILE_SIZE)
        return byteArrayOf(
            0x00, 0x0F,                              // CCLEN = 15
            0x20,                                    // версия 2.0
            0x00, 0x3B,                              // MLe — сколько читать за раз
            0x00, 0x34,                              // MLc — сколько писать за раз
            0x04, 0x06,                              // TLV: контроль NDEF (6 байт)
            0xE1.toByte(), 0x04,                     // файл E104
            ((maxSize shr 8) and 0xFF).toByte(), (maxSize and 0xFF).toByte(),
            0x00,                                    // чтение разрешено
            0x00,                                    // запись запрещена (метка только для чтения)
        )
    }

    /** Студент прочитал метку целиком: это и есть касание. */
    private fun onMessageServed() {
        messageServed = true
        if (tapRecorded) return
        tapRecorded = true

        val sessionId = ndefSessionId ?: return
        vibrate()
        TerminalState.recordTap(applicationContext, sessionId, ndefTimestamp)
        Log.i(TAG, "Касание зафиксировано: пара $sessionId, время $ndefTimestamp")
    }

    override fun onDeactivated(reason: Int) {
        // соединение закрыто: следующие касания — новые студенты
        selectedFile = SelectedFile.NONE
        messageServed = false
        tapRecorded = false
    }

    private fun vibrate() {
        runCatching {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        private const val TAG = "TerminalCardService"

        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
        private const val INS_UPDATE_BINARY = 0xD6

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_WRONG_PARAMETERS = byteArrayOf(0x6B.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())

        /** Стандартный AID NDEF Type 4 Tag — его выбирает iPhone/CoreNFC. */
        private val AID_NDEF_TYPE4 = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
        )

        /** Собственный AID проекта Visits11 (совместимость со старыми считывателями). */
        private val AID_VISITS11 = byteArrayOf(
            0xF0.toByte(), 0x39, 0x11, 0x01, 0x02, 0x03, 0x04,
        )

        private val FILE_ID_CC = byteArrayOf(0xE1.toByte(), 0x03)
        private val FILE_ID_NDEF = byteArrayOf(0xE1.toByte(), 0x04)
    }
}
