package ru.kubgau.attendance.terminal.ble

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Протокол BLE-метки — «NFC, которого нет»: студент прикладывает телефон,
 * приложение соединяется с терминалом и отдаёт отметку.
 *
 * Как устроено (всё согласовано с iOS-приложением):
 *
 *   1. Терминал вещает (advertising) сервис [SERVICE_UUID] и кладёт в
 *      Service Data 8-байтовый токен: HMAC(секрет сессии, окно времени).
 *      Токен меняется каждые [TOKEN_TTL_SECONDS] секунд — записать заранее нельзя.
 *   2. Студент сканирует эфир, находит сервис, проверяет уровень сигнала (RSSI)
 *      и, если телефон действительно поднесли, подключается (GATT).
 *   3. Читает характеристику [INFO_UUID]: JSON с данными пары и минимально
 *      допустимым RSSI.
 *   4. Пишет в [MARK_UUID] свой JSON: device_id, время, токен, измеренный RSSI.
 *      Если payload длиннее одного пакета — частями (1 байт: 0x01 «продолжение», 0x00 «конец»).
 *   5. Терминал проверяет токен и записывает касание; в [ACK_UUID] уходит ответ.
 *
 * Токен защищает от заранее подготовленных записей, а RSSI и малое излучение
 * (ULTRA_LOW) — от отметок из коридора.
 */
object BleProtocol {

    /** Короткий 16-битный UUID: экономит место в 31-байтном пакете рекламы. */
    private const val BASE = "-0000-1000-8000-00805f9b34fb"

    val SERVICE_UUID: UUID = UUID.fromString("0000a110$BASE")
    val INFO_UUID: UUID = UUID.fromString("0000a111$BASE")
    val MARK_UUID: UUID = UUID.fromString("0000a112$BASE")
    val ACK_UUID: UUID = UUID.fromString("0000a113$BASE")
    val CCCD_UUID: UUID = UUID.fromString("00002902$BASE")

    /** Сколько живёт токен в эфире; окно = 5 секунд. */
    const val TOKEN_TTL_SECONDS = 5L

    /** Размер токена в Service Data. */
    const val TOKEN_SIZE = 8

    /** Коды ответа терминала студенту. */
    const val ACK_OK = 0
    const val ACK_BAD_TOKEN = 2
    const val ACK_NOT_EMULATING = 3
    const val ACK_RATE_LIMITED = 4

    // ------------------------------------------------------------------ токены

    /** Номер окна времени. */
    fun window(epochSecond: Long): Long = epochSecond / TOKEN_TTL_SECONDS

    /** Токен для окна [windowIndex]: первые [TOKEN_SIZE] байт HMAC-SHA256. */
    fun token(secret: ByteArray, windowIndex: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal(windowIndex.toString().toByteArray())
        return digest.copyOf(TOKEN_SIZE)
    }

    /** Токен текущего окна. */
    fun currentToken(secret: ByteArray, epochSecond: Long = System.currentTimeMillis() / 1000): ByteArray =
        token(secret, window(epochSecond))

    /**
     * Подходит ли токен, присланный студентом.
     * Принимаем текущее окно и предыдущее — телефоны не обязаны иметь один и тот же
     * момент включения окна, да и касание могло начаться за секунду до смены токена.
     */
    fun tokenMatches(secret: ByteArray, received: ByteArray, epochSecond: Long = System.currentTimeMillis() / 1000): Boolean {
        if (received.size != TOKEN_SIZE) return false
        val current = window(epochSecond)
        return MessageDigest.isEqual(received, token(secret, current)) ||
            MessageDigest.isEqual(received, token(secret, current - 1))
    }

    /** Токен в hex — так он ходит в JSON (16 символов). */
    fun tokenToHex(token: ByteArray): String =
        token.joinToString("") { "%02x".format(it) }

    fun tokenFromHex(hex: String): ByteArray? {
        val clean = hex.trim().lowercase()
        if (clean.length != TOKEN_SIZE * 2) return null
        return runCatching {
            ByteArray(TOKEN_SIZE) { index ->
                clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    /** Ответ терминала студенту: компактный JSON. */
    fun ackJson(code: Int, message: String, sessionId: String? = null): String = buildString {
        append('{')
        append("\"code\":").append(code)
        append(",\"message\":\"").append(message.replace("\"", "'")).append('"')
        if (sessionId != null) {
            append(",\"session_id\":\"").append(sessionId).append('"')
        }
        append('}')
    }
}
