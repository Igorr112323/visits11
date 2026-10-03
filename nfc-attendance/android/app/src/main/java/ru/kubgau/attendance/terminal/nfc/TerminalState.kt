package ru.kubgau.attendance.terminal.nfc

import android.content.Context
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.AttendanceApp
import ru.kubgau.attendance.terminal.data.AppDatabase
import ru.kubgau.attendance.terminal.data.SessionEntity
import ru.kubgau.attendance.terminal.data.TapEntity
import ru.kubgau.attendance.terminal.net.SyncEngine
import java.time.OffsetDateTime

/**
 * Общее состояние терминала: какая пара сейчас «висит» на метке.
 *
 * Экран обновляет это состояние, а [TerminalCardService] только читает —
 * поэтому HCE отвечает мгновенно и не зависит от того, нарисован ли интерфейс.
 */
object TerminalState {

    @Volatile
    var activeSession: SessionEntity? = null

    @Volatile
    var emulating: Boolean = false

    /** Сколько касаний записано за текущую пару (для интерфейса). */
    @Volatile
    var tapCount: Int = 0

    fun startEmulation(session: SessionEntity) {
        activeSession = session
        emulating = true
        tapCount = 0
    }

    fun stopEmulation() {
        emulating = false
    }

    /**
     * Студент успешно прочитал метку: сохраняем касание в Room и сразу отправляем
     * на сервер — по нему сервер подтвердит, что отметка студента настоящая.
     */
    fun recordTap(context: Context, sessionId: String, tapTime: String, result: String = "read") {
        tapCount += 1
        val app = context.applicationContext
        AttendanceApp.scope.launch {
            AppDatabase.get(app).tapDao().insert(
                TapEntity(sessionId = sessionId, tapTime = tapTime, result = result),
            )
            SyncEngine.syncAfterTap(app)
        }
    }

    /** ISO-8601 с локальной таймзоной — тот же формат, что у сервера и iOS. */
    fun nowIso(): String = OffsetDateTime.now().withNano(0).toString()
}
