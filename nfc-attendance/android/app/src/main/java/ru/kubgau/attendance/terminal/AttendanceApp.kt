package ru.kubgau.attendance.terminal

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.data.AppDatabase
import ru.kubgau.attendance.terminal.nfc.TerminalState
import ru.kubgau.attendance.terminal.net.SyncEngine

/**
 * Точка входа приложения.
 *
 * Здесь живёт «долгоживущий» scope: запись касаний и отправка на сервер должны
 * завершиться, даже если экран уже закрыт, а HCE-сервис уничтожен системой.
 */
class AttendanceApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        SyncEngine.configure(preferences.getString(KEY_SERVER_URL, "") ?: "")

        // Подхватываем активную пару (если телефон перезапустили во время занятия)
        scope.launch {
            AppDatabase.get(this@AttendanceApp).sessionDao().activeSession()?.let { session ->
                TerminalState.activeSession = session
            }
            // и пробуем дослать всё, что осталось с прошлого раза
            if (preferences.getString(KEY_SERVER_URL, "").orEmpty().isNotBlank()) {
                SyncEngine.syncNow(this@AttendanceApp)
            }
        }
    }

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TEACHER_ID = "teacher_id"

        /** Scope на всё приложение: переживает поворот экрана и уход в фон. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun preferences(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }
}
