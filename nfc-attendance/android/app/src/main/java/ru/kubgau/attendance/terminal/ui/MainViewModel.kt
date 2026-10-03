package ru.kubgau.attendance.terminal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.AttendanceApp
import ru.kubgau.attendance.terminal.ble.BleRange
import ru.kubgau.attendance.terminal.ble.BleTerminalService
import ru.kubgau.attendance.terminal.data.AppDatabase
import ru.kubgau.attendance.terminal.data.SessionEntity
import ru.kubgau.attendance.terminal.net.PresentDto
import ru.kubgau.attendance.terminal.net.SyncEngine
import ru.kubgau.attendance.terminal.nfc.TerminalState
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Логика экрана преподавателя: создать пару, включить эмуляцию метки,
 * показать отметившихся, синхронизировать с сервером.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val serverUrl: String = "",
        val serverOnline: Boolean? = null,          // null — ещё не проверяли
        val subject: String = "",
        val group: String = "",
        val teacherId: String = "",
        val minutes: String = "120",
        val session: SessionEntity? = null,
        val emulating: Boolean = false,
        val elapsed: String = "",
        val tapCount: Int = 0,
        val present: List<PresentDto> = emptyList(),
        val presentCount: Int = 0,
        val message: String = "",
        val syncState: String = "",
        val busy: Boolean = false,
        val bleActive: Boolean = false,
        val bleStatus: String = "",
        val lastStudent: String = "",
        val range: BleRange = BleRange.TOUCH,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val database = AppDatabase.get(application)
    private var clockJob: Job? = null
    private var presentJob: Job? = null

    init {
        val preferences = AttendanceApp.preferences(application)
        _state.value = _state.value.copy(
            serverUrl = preferences.getString(AttendanceApp.KEY_SERVER_URL, "").orEmpty(),
            teacherId = preferences.getString(AttendanceApp.KEY_TEACHER_ID, "").orEmpty(),
        )

        // выбранный диапазон «насколько близко прикладывать телефон»
        val storedRange = BleRange.fromName(
            preferences.getString(KEY_RANGE, BleRange.TOUCH.name),
        )
        TerminalState.range = storedRange
        _state.value = _state.value.copy(
            range = storedRange,
            bleActive = TerminalState.bleActive,
            bleStatus = TerminalState.bleStatus,
        )

        // активная пара из прошлого запуска приложения
        viewModelScope.launch {
            database.sessionDao().activeSession()?.let { session ->
                TerminalState.activeSession = session
                _state.value = _state.value.copy(
                    session = session,
                    subject = session.subject,
                    group = session.groupName.orEmpty(),
                    teacherId = session.teacherId,
                    minutes = session.minutes.toString(),
                    emulating = TerminalState.emulating,
                    tapCount = TerminalState.tapCount,
                )
                startClock()
                startPresentPolling(session.id)
            }
        }

        viewModelScope.launch {
            SyncEngine.state.collect { syncState ->
                val text = when (syncState) {
                    is SyncEngine.State.Idle -> ""
                    is SyncEngine.State.Syncing -> "Отправка на сервер…"
                    is SyncEngine.State.Ok -> syncState.message
                    is SyncEngine.State.Failed -> syncState.message
                }
                _state.value = _state.value.copy(syncState = text)
            }
        }
    }

    // ------------------------------------------------------------------ ввод

    fun onServerUrlChanged(value: String) = update(url = value)
    fun onSubjectChanged(value: String) = update(subjectInput = value)
    fun onGroupChanged(value: String) = update(groupInput = value)
    fun onTeacherChanged(value: String) = update(teacherInput = value)
    fun onMinutesChanged(value: String) = update(minutesInput = value)

    fun saveServerUrl() {
        val url = normalizeUrl(_state.value.serverUrl)
        AttendanceApp.preferences(getApplication())
            .edit().putString(AttendanceApp.KEY_SERVER_URL, url).apply()
        SyncEngine.configure(url)
        _state.value = _state.value.copy(serverUrl = url, message = "Адрес сохранён: $url")
        viewModelScope.launch { checkServer() }
    }

    suspend fun checkServer(): Boolean {
        val url = SyncEngine.serverUrl.value
        if (url.isBlank()) {
            _state.value = _state.value.copy(serverOnline = false, message = "Введите адрес сервера")
            return false
        }
        val ok = SyncEngine.ping(url)
        _state.value = _state.value.copy(
            serverOnline = ok,
            message = if (ok) "Сервер отвечает: $url" else "Сервер не отвечает. Проверьте IP и что запущен uvicorn.",
        )
        return ok
    }

    // ---------------------------------------------------------------- пара

    fun createSession() {
        val current = _state.value
        if (current.subject.isBlank()) {
            _state.value = current.copy(message = "Укажите предмет")
            return
        }
        if (current.teacherId.isBlank()) {
            _state.value = current.copy(message = "Укажите ваш идентификатор (например, IVANOV_II)")
            return
        }

        val minutes = current.minutes.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 120
        val start = OffsetDateTime.now().withNano(0)
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),          // случайный id — защита от заготовок
            subject = current.subject.trim(),
            teacherId = current.teacherId.trim(),
            groupName = current.group.trim().ifBlank { null },
            startTime = start.toString(),
            endTime = start.plusMinutes(minutes.toLong()).toString(),
            minutes = minutes,
        )

        _state.value = current.copy(busy = true)
        viewModelScope.launch {
            database.sessionDao().upsert(session)
            AttendanceApp.preferences(getApplication())
                .edit().putString(AttendanceApp.KEY_TEACHER_ID, session.teacherId).apply()

            TerminalState.startEmulation(session)
            startBleIfPossible()
            val pushed = SyncEngine.pushSession(getApplication(), session)

            _state.value = _state.value.copy(
                session = session,
                emulating = true,
                tapCount = 0,
                busy = false,
                bleActive = TerminalState.bleActive,
                bleStatus = TerminalState.bleStatus,
                message = buildString {
                    append("Пара создана. Метка активна — приложите телефон студента.")
                    if (!pushed) append("\nСервер недоступен: пара сохранена и уедет при синхронизации.")
                },
            )
            startClock()
            startPresentPolling(session.id)
            SyncEngine.syncAfterTap(getApplication())
        }
    }

    fun toggleEmulation() {
        val current = _state.value
        val session = current.session ?: run {
            _state.value = current.copy(message = "Сначала создайте пару")
            return
        }
        if (current.emulating) {
            TerminalState.stopEmulation()
            BleTerminalService.stop(getApplication())
            _state.value = current.copy(
                emulating = false,
                bleActive = false,
                bleStatus = TerminalState.bleStatus,
                message = "Эмуляция выключена",
            )
        } else {
            TerminalState.startEmulation(session)
            startBleIfPossible()
            _state.value = _state.value.copy(
                emulating = true,
                bleActive = TerminalState.bleActive,
                bleStatus = TerminalState.bleStatus,
                message = "Метка активна — приложите телефон студента к этому телефону",
            )
        }
    }

    /** Диапазон: чем строже, тем меньше шансов отметиться из коридора. */
    fun onRangeChanged(range: BleRange) {
        TerminalState.range = range
        AttendanceApp.preferences(getApplication()).edit()
            .putString(KEY_RANGE, range.name).apply()
        _state.value = _state.value.copy(range = range)
        if (_state.value.emulating) {
            // перезапускаем вещание с новой мощностью
            BleTerminalService.stop(getApplication())
            startBleIfPossible()
            _state.value = _state.value.copy(
                bleActive = TerminalState.bleActive,
                bleStatus = TerminalState.bleStatus,
                message = "Диапазон: ${range.label.lowercase()}",
            )
        }
    }

    /** Разрешения выданы — можно поднимать метку. */
    fun onPermissionsGranted() {
        _state.value = _state.value.copy(bleStatus = TerminalState.bleStatus)
        if (_state.value.emulating) startBleIfPossible()
    }

    private fun startBleIfPossible() {
        val context = getApplication<Application>()
        BleTerminalService.start(context)
        _state.value = _state.value.copy(
            bleActive = TerminalState.bleActive,
            bleStatus = TerminalState.bleStatus,
        )
    }

    fun closeSession() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            val endTime = OffsetDateTime.now().withNano(0).toString()
            database.sessionDao().close(session.id, endTime)
            TerminalState.stopEmulation()
            BleTerminalService.stop(getApplication())
            TerminalState.activeSession = null
            SyncEngine.pushClose(getApplication(), session.id)
            SyncEngine.syncAfterTap(getApplication())
            stopPolling()
            _state.value = _state.value.copy(
                session = null, emulating = false, present = emptyList(),
                presentCount = 0, elapsed = "", bleActive = false,
                bleStatus = TerminalState.bleStatus,
                message = "Пара закрыта. Новые отметки не принимаются.",
            )
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            SyncEngine.syncNow(getApplication())
            _state.value.session?.let { refreshPresent(it.id) }
        }
    }

    fun refreshPresentNow() {
        viewModelScope.launch { _state.value.session?.let { refreshPresent(it.id) } }
    }

    // ------------------------------------------------------------- отображение

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                val session = _state.value.session ?: break
                val start = runCatching { OffsetDateTime.parse(session.startTime) }.getOrNull()
                val elapsed = start?.let {
                    val seconds = java.time.Duration.between(it, OffsetDateTime.now()).seconds.coerceAtLeast(0)
                    "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                }.orEmpty()
                _state.value = _state.value.copy(
                    elapsed = elapsed,
                    tapCount = TerminalState.tapCount,
                    bleActive = TerminalState.bleActive,
                    bleStatus = TerminalState.bleStatus,
                    lastStudent = TerminalState.lastStudentName,
                )
                delay(1_000)
            }
        }
    }

    private fun startPresentPolling(sessionId: String) {
        stopPolling()
        presentJob = viewModelScope.launch {
            while (true) {
                refreshPresent(sessionId)
                delay(5_000)
            }
        }
    }

    private fun stopPolling() {
        presentJob?.cancel()
        presentJob = null
    }

    private suspend fun refreshPresent(sessionId: String) {
        val response = SyncEngine.fetchPresent(sessionId) ?: return
        _state.value = _state.value.copy(
            present = response.present.reversed(),
            presentCount = response.presentCount,
        )
    }

    override fun onCleared() {
        stopPolling()
        clockJob?.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------ мелочи

    private fun update(
        url: String? = null,
        subjectInput: String? = null,
        groupInput: String? = null,
        teacherInput: String? = null,
        minutesInput: String? = null,
    ) {
        val current = _state.value
        _state.value = current.copy(
            serverUrl = url ?: current.serverUrl,
            subject = subjectInput ?: current.subject,
            group = groupInput ?: current.group,
            teacherId = teacherInput ?: current.teacherId,
            minutes = minutesInput ?: current.minutes,
        )
    }

    private companion object {
        const val KEY_RANGE = "ble_range"
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (!url.contains(":8000") && !url.substringAfter("://").contains(':') &&
            !url.substringAfter("://").contains('/')
        ) {
            url = "$url:8000"
        }
        return url.removeSuffix("/")
    }
}
