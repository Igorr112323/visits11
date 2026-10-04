package ru.kubgau.attendance.terminal.nfc

import android.content.Context
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.AttendanceApp
import ru.kubgau.attendance.terminal.ble.BleRange
import ru.kubgau.attendance.terminal.data.AppDatabase
import ru.kubgau.attendance.terminal.data.SessionEntity
import ru.kubgau.attendance.terminal.data.TapEntity
import ru.kubgau.attendance.terminal.net.SyncEngine
import java.time.OffsetDateTime

/**
 * Общее состояние терминала: какая пара сейчас «висит» на метке.
 *
 * Экран обновляет это состояние, а сервисы метки (NFC HCE и BLE) только читают —
 * поэтому касание обрабатывается мгновенно, даже если экран свёрнут.
 */
object TerminalState {

    @Volatile
    var activeSession: SessionEntity? = null

    @Volatile
    var emulating: Boolean = false

    /** Сколько касаний записано за текущую пару (для интерфейса). */
    @Volatile
    var tapCount: Int = 0

    /** Состояние BLE-метки: активна ли и что говорит Bluetooth. */
    @Volatile
    var bleActive: Boolean = false

    @Volatile
    var bleStatus: String = "BLE-метка выключена"

    /** Кого касались последним — показываем преподавателю. */
    @Volatile
    var lastStudentName: String = ""

    /** Насколько близко нужно приложить телефон (влияет на мощность вещания). */
    @Volatile
    var range: BleRange = BleRange.TOUCH

    fun startEmulation(session: SessionEntity) {
        activeSession = session
        emulating = true
        tapCount = 0
        lastStudentName = ""
    }

    fun stopEmulation() {
        emulating = false
    }

    /**
     * Кто-то приложил телефон (NFC или BLE): сохраняем касание в Room и сразу
     * отправляем на сервер — по нему сервер подтверждает отметку студента.
     *
     * @param deviceId телефон студента (для BLE-касаний): сервер сверит, что отметку
     *   прислал именно он, и что сигнал был достаточно сильным.
     */
    fun recordTap(
        context: Context,
        sessionId: String,
        tapTime: String,
        result: String = "read",
        deviceId: String? = null,
        rssi: Int? = null,
        studentName: String? = null,
    ) {
        tapCount += 1
        if (!studentName.isNullOrBlank()) lastStudentName = studentName

        val app = context.applicationContext
        AttendanceApp.scope.launch {
            AppDatabase.get(app).tapDao().insert(
                TapEntity(
                    sessionId = sessionId,
                    tapTime = tapTime,
                    result = result,
                    deviceId = deviceId,
                    rssi = rssi,
                ),
            )
            SyncEngine.syncAfterTap(app)
        }
    }

    /** ISO-8601 с локальной таймзоной — тот же формат, что у сервера и iOS. */
    fun nowIso(): String = OffsetDateTime.now().withNano(0).toString()
}
