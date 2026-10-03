package ru.kubgau.attendance.terminal.ble

import android.bluetooth.le.AdvertiseSettings

/**
 * Насколько «близко» нужно приложить телефон — компромисс между удобством
 * и защитой от отметок из коридора.
 *
 * Работает двумя рычагами:
 *   • [txPower] — реальная мощность излучения терминала. На ULTRA_LOW сигнал
 *     физически не достаёт до коридора (единицы метров через стены);
 *   • [minRssi] — порог, который проверяет приложение студента: пока сигнал
 *     слабее, оно не отправляет отметку (в терминал едет только RSSI для журнала).
 *
 * ВНИМАНИЕ: порог проверяется на стороне студента (Android-as-GATT-server не
 * умеет измерять RSSI подключившегося клиента). Поэтому главная защита — мощность
 * излучения, а RSSI нужен для аудита: сервер помечает слабые отметки как
 * неподтверждённые (WEAK_RSSI_FLOOR).
 */
enum class BleRange(
    val label: String,
    val hint: String,
    val txPower: Int,
    val minRssi: Int,
    /** Соответствие порога режиму Android (для UI). */
    val powerLabel: String,
) {
    /** «Приложить вплотную» — режим по умолчанию: имитация NFC. */
    TOUCH(
        label = "Вплотную",
        hint = "5–10 см, как NFC. Отметки из коридора невозможны",
        txPower = AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
        minRssi = -60,
        powerLabel = "минимальная мощность",
    ),
    NEAR(
        label = "Рядом",
        hint = "до ~0,5 м: телефон на столе рядом с терминалом",
        txPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
        minRssi = -68,
        powerLabel = "низкая мощность",
    ),
    WIDE(
        label = "Свободно",
        hint = "до ~2 м. Больше шансов, что отметится кто-то из коридора",
        txPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
        minRssi = -78,
        powerLabel = "средняя мощность",
    );

    companion object {
        fun fromName(name: String?): BleRange =
            entries.firstOrNull { it.name == name } ?: TOUCH
    }
}
