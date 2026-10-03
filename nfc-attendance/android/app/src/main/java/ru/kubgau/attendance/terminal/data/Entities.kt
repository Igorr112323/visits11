package ru.kubgau.attendance.terminal.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Пара (занятие). Создаётся на телефоне преподавателя и уезжает на сервер.
 * id — случайный UUID: такую «метку» невозможно заготовить заранее.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val teacherId: String,
    val groupName: String?,
    val startTime: String,          // ISO-8601, локальное время телефона
    val endTime: String?,
    val minutes: Int = 120,
    val status: String = STATUS_ACTIVE,
    val synced: Boolean = false,    // пара уже уехала на сервер
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_CLOSED = "closed"
    }
}

/**
 * Касание: студент приложил телефон к метке (успешно прочитал NDEF-сообщение).
 * Сервер по этим касаниям подтверждает, что отметка настоящая.
 */
@Entity(tableName = "taps")
data class TapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val tapTime: String,            // ISO-8601, момент касания (часы терминала)
    val result: String = "read",    // read (NFC) | ble
    val deviceId: String? = null,   // телефон студента (для BLE-касаний)
    val rssi: Int? = null,          // уровень сигнала в дБм — по нему сервер отсекает «коридор»
    val synced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
