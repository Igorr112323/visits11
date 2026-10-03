package ru.kubgau.attendance.terminal.net

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Клиент локального сервера преподавателя (FastAPI, порт 8000).
 * Адрес подставляется в каждый вызов (@Url) — его вводят в приложении,
 * поэтому перезапуск сервера с другим IP ничего не ломает.
 */
interface AttendanceApi {

    @GET
    suspend fun health(@Url url: String): HealthResponse

    /** Создать пару (id — UUID, сгенерированный телефоном). */
    @POST
    suspend fun createSession(@Url url: String, @Body body: SessionRequest): SessionResponse

    /** Полная синхронизация: пары + касания. Идемпотентно. */
    @POST
    suspend fun sync(@Url url: String, @Body body: SyncRequest): SyncResponse

    /** Что сервер уже знает (чтобы не гонять лишнее). */
    @GET
    suspend fun syncState(
        @Url url: String,
        @Query("teacher_id") teacherId: String,
    ): SyncStateResponse

    /** Список присутствующих на паре. */
    @GET
    suspend fun attendance(@Url url: String): AttendanceResponse

    /** Закрыть пару: новые отметки не принимаются. */
    @POST
    suspend fun closeSession(@Url url: String, @Body body: CloseRequest): SessionResponse
}

// ------------------------------------------------------------------ DTO сервера

data class HealthResponse(
    val app: String? = null,
    val ok: Boolean = false,
    val time: String? = null,
)

data class SessionRequest(
    @SerializedName("session_id") val sessionId: String,
    val subject: String,
    @SerializedName("teacher_id") val teacherId: String,
    @SerializedName("group_name") val groupName: String?,
    val minutes: Int,
)

data class SessionDto(
    val id: String,
    val subject: String,
    @SerializedName("teacher_id") val teacherId: String,
    @SerializedName("group_name") val groupName: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    val status: String? = null,
    @SerializedName("present_count") val presentCount: Int? = null,
    @SerializedName("is_open") val isOpen: Boolean? = null,
)

data class SessionResponse(val ok: Boolean = false, val session: SessionDto? = null)

data class CloseRequest(@SerializedName("session_id") val sessionId: String)

data class SyncTap(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("tap_time") val tapTime: String,
    val result: String? = "read",
    val source: String = "android",
    /** Телефон студента, который коснулся терминала (BLE-режим). */
    @SerializedName("device_id") val deviceId: String? = null,
    /** Уровень сигнала в дБм: сервер помечает слабые отметки как неподтверждённые. */
    val rssi: Int? = null,
)

data class SyncRequest(
    @SerializedName("teacher_id") val teacherId: String,
    val sessions: List<Map<String, Any?>>,
    val taps: List<SyncTap>,
)

data class SyncResponse(
    val ok: Boolean = false,
    @SerializedName("sessions_created") val sessionsCreated: Int = 0,
    @SerializedName("taps_added") val tapsAdded: Int = 0,
    @SerializedName("marks_verified") val marksVerified: Int = 0,
)

data class SyncStateResponse(
    val ok: Boolean = false,
    @SerializedName("session_ids") val sessionIds: List<String> = emptyList(),
    @SerializedName("tap_keys") val tapKeys: List<String> = emptyList(),
)

data class PresentDto(
    @SerializedName("student_id") val studentId: String,
    @SerializedName("student_name") val studentName: String? = null,
    val timestamp: String? = null,
    val verified: Int = 0,
    val source: String? = null,
)

data class AttendanceResponse(
    val ok: Boolean = false,
    @SerializedName("present_count") val presentCount: Int = 0,
    val present: List<PresentDto> = emptyList(),
    val absent: List<PresentDto> = emptyList(),
)
