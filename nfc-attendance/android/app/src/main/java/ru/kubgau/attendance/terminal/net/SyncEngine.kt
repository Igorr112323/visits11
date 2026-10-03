package ru.kubgau.attendance.terminal.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kubgau.attendance.terminal.data.AppDatabase
import ru.kubgau.attendance.terminal.data.SessionEntity

/**
 * Отправка данных на локальный сервер.
 *
 * • пары и касания лежат в Room, пока не уедут (переживают отсутствие сети);
 * • повторные попытки — с растущей паузой (1, 2, 4 … 30 секунд);
 * • syncNow() вызывается при каждом касании, вручную кнопкой и при старте.
 */
object SyncEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Состояние связи для интерфейса. */
    sealed interface State {
        data object Idle : State
        data object Syncing : State
        data class Ok(val message: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl

    private var retryJob: kotlinx.coroutines.Job? = null

    fun configure(url: String) {
        _serverUrl.value = url.trim().removeSuffix("/")
    }

    /** Проверка связи: GET /api/health. */
    suspend fun ping(url: String = _serverUrl.value): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        runCatching { ApiFactory.api(url).health("$url/api/health").ok }.getOrDefault(false)
    }

    /** Создать пару на сервере (телефон — источник правды, если сервер недоступен). */
    suspend fun pushSession(context: Context, session: SessionEntity): Boolean =
        withContext(Dispatchers.IO) {
            val url = _serverUrl.value
            if (url.isBlank()) return@withContext false
            runCatching {
                ApiFactory.api(url).createSession(
                    "$url/api/session",
                    SessionRequest(
                        sessionId = session.id,
                        subject = session.subject,
                        teacherId = session.teacherId,
                        groupName = session.groupName,
                        minutes = session.minutes,
                    ),
                ).ok
            }.getOrDefault(false).also { ok ->
                if (ok) AppDatabase.get(context).sessionDao().markSynced(session.id)
            }
        }

    /** Закрыть пару на сервере (не критично, если не получилось — уедет при синхронизации). */
    suspend fun pushClose(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        val url = _serverUrl.value
        if (url.isBlank()) return@withContext
        runCatching {
            ApiFactory.api(url).closeSession("$url/api/session/$sessionId/close", CloseRequest(sessionId))
        }
    }

    /** Список присутствующих (то, что уже знает сервер). */
    suspend fun fetchPresent(sessionId: String): AttendanceResponse? = withContext(Dispatchers.IO) {
        val url = _serverUrl.value
        if (url.isBlank()) return@withContext null
        runCatching { ApiFactory.api(url).attendance("$url/api/attendance/$sessionId") }.getOrNull()
    }

    /**
     * Отправляет всё неотправленное: POST /api/sync {sessions, taps}.
     * Возвращает true, если очередь пуста.
     */
    suspend fun syncNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val url = _serverUrl.value
        if (url.isBlank()) {
            _state.value = State.Failed("Не задан адрес сервера")
            return@withContext false
        }

        val database = AppDatabase.get(context)
        val sessions = database.sessionDao().unsynced()
        val taps = database.tapDao().unsynced()
        if (sessions.isEmpty() && taps.isEmpty()) {
            _state.value = State.Ok("Всё синхронизировано")
            return@withContext true
        }

        _state.value = State.Syncing
        val teacherId = sessions.firstOrNull()?.teacherId
            ?: android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getString("teacher_id", "IVANOV_II")
            ?: "IVANOV_II"

        runCatching {
            ApiFactory.api(url).sync(
                "$url/api/sync",
                SyncRequest(
                    teacherId = teacherId,
                    sessions = sessions.map { session ->
                        mapOf(
                            "id" to session.id,
                            "subject" to session.subject,
                            "teacher_id" to session.teacherId,
                            "group_name" to session.groupName,
                            "start_time" to session.startTime,
                            "end_time" to session.endTime,
                            "status" to session.status,
                            "minutes" to session.minutes,
                        )
                    },
                    taps = taps.map { tap ->
                        SyncTap(
                            sessionId = tap.sessionId,
                            tapTime = tap.tapTime,
                            result = tap.result,
                            source = "android",
                            deviceId = tap.deviceId,
                            rssi = tap.rssi,
                        )
                    },
                ),
            )
        }.onSuccess { response ->
            sessions.forEach { database.sessionDao().markSynced(it.id) }
            taps.forEach { database.tapDao().markSynced(it.id) }
            _state.value = State.Ok(
                "Отправлено: пар ${response.sessionsCreated}, касаний ${response.tapsAdded}, " +
                    "подтверждено отметок ${response.marksVerified}",
            )
        }.onFailure { error ->
            _state.value = State.Failed("Сервер недоступен: ${error.message ?: "нет связи"}")
            scheduleRetry(context)
        }

        return@withContext _state.value is State.Ok
    }

    /** Фоновая повторная отправка, если сервер был недоступен. */
    fun scheduleRetry(context: Context) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var pause = 1_000L
            while (true) {
                delay(pause)
                if (syncNow(context)) break
                pause = (pause * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Сразу после касания: сначала быстрая попытка, потом — по расписанию. */
    fun syncAfterTap(context: Context) {
        scope.launch { if (!syncNow(context)) scheduleRetry(context) }
    }
}
