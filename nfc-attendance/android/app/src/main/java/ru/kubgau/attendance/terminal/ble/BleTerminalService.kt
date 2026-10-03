package ru.kubgau.attendance.terminal.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ru.kubgau.attendance.terminal.R
import ru.kubgau.attendance.terminal.nfc.TerminalState
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE-метка: пока пара идёт, телефон преподавателя вещает сервис и принимает
 * касания студентов. Для студента это выглядит как NFC: поднёс телефон — отметился.
 *
 * Как устроено:
 *   • advertising: сервис [BleProtocol.SERVICE_UUID] + Service Data с токеном,
 *     который меняется каждые 5 секунд. Мощность излучения задаёт [BleRange]:
 *     в режиме «Вплотную» сигнал физически не достаёт до коридора;
 *   • GATT-сервер: INFO (read) — данные пары, MARK (write) — отметка студента,
 *     ACK (notify) — ответ терминала;
 *   • каждое принятое касание превращается в запись Room и сразу уезжает на сервер
 *     вместе с device_id и измеренным RSSI — сервер по ним подтверждает отметку.
 */
class BleTerminalService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var service: BluetoothGattService? = null

    private var infoCharacteristic: BluetoothGattCharacteristic? = null
    private var markCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null

    /** Секрет текущей пары: по нему считаем токены в эфире. */
    @Volatile
    private var tokenSecret: ByteArray = ByteArray(0)

    /** Буферы частичных записей (payload может не влезть в один пакет). */
    private val writeBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val notifyEnabled = ConcurrentHashMap<String, Boolean>()
    private val lastMarkAt = ConcurrentHashMap<String, Long>()

    private val tokenTicker = object : Runnable {
        override fun run() {
            if (TerminalState.emulating) {
                startAdvertising()
                handler.postDelayed(this, BleProtocol.TOKEN_TTL_SECONDS * 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startBle()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAdvertising()
        stopGattServer()
        TerminalState.bleActive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------- запуск BLE

    private fun startBle() {
        if (!hasPermissions()) {
            Log.w(TAG, "Нет разрешений Bluetooth — BLE-метка не поднялась")
            TerminalState.bleActive = false
            TerminalState.bleStatus = "Нет разрешений Bluetooth (выдайте их в приложении)"
            return
        }

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter!!.isEnabled) {
            TerminalState.bleActive = false
            TerminalState.bleStatus = "Bluetooth выключен — включите его для отметок"
            return
        }

        tokenSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        advertiser = adapter?.bluetoothLeAdvertiser

        if (!startGattServer()) return
        startAdvertising()

        TerminalState.bleActive = true
        TerminalState.bleStatus = "BLE-метка активна (${TerminalState.range.label.lowercase()})"
        handler.removeCallbacks(tokenTicker)
        handler.postDelayed(tokenTicker, BleProtocol.TOKEN_TTL_SECONDS * 1000L)
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startGattServer(): Boolean {
        val manager = bluetoothManager ?: return false
        gattServer = manager.openGattServer(this, callbacks) ?: return false

        val gattService = BluetoothGattService(
            BleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        infoCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        markCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.MARK_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        ackCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.ACK_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        ackCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(
                BleProtocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )

        gattService.addCharacteristic(infoCharacteristic)
        gattService.addCharacteristic(markCharacteristic)
        gattService.addCharacteristic(ackCharacteristic)
        service = gattService

        return runCatching { gattServer?.addService(gattService) }.getOrDefault(false)
    }

    private fun startAdvertising() {
        val leAdvertiser = advertiser ?: return
        val session = TerminalState.activeSession ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(TerminalState.range.txPower)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val token = BleProtocol.currentToken(tokenSecret)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .addServiceData(ParcelUuid(BleProtocol.SERVICE_UUID), token)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        runCatching {
            leAdvertiser.stopAdvertising(advertiseCallback)
            leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        }.onFailure {
            Log.w(TAG, "Не удалось запустить рекламу BLE: ${it.message}")
            TerminalState.bleStatus = "Bluetooth не даёт вещать (${it.message})"
        }
        Log.d(TAG, "Вещание: пара ${session.id.take(8)}…, токен ${BleProtocol.tokenToHex(token)}")
    }

    private fun stopAdvertising() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
    }

    private fun stopGattServer() {
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        service = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE-метка в эфире")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Ошибка advertising: $errorCode")
            TerminalState.bleStatus = "Ошибка Bluetooth-вещания (код $errorCode)"
        }
    }

    // ------------------------------------------------------- колбэки GATT-сервера

    private val callbacks = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                writeBuffers.remove(address)
                Log.i(TAG, "Студент подключился: $address")
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                writeBuffers.remove(address)
                notifyEnabled.remove(address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.d(TAG, "MTU с ${device?.address}: $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val server = gattServer ?: return
            if (device == null || characteristic == null) return

            if (characteristic.uuid == BleProtocol.INFO_UUID) {
                val payload = infoJson().toByteArray()
                val slice = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val server = gattServer ?: return
            if (device == null || descriptor == null) return

            if (descriptor.uuid == BleProtocol.CCCD_UUID) {
                // студент включал/выключал уведомления (по ним уходит ACK)
                val first = value?.firstOrNull()?.toInt() ?: 0
                val enabled = first == 0x01 || first == 0x02
                notifyEnabled[device.address] = enabled
                Log.d(TAG, "Уведомления для ${device.address}: $enabled")
                if (responseNeeded) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val server = gattServer ?: return
            if (device == null || characteristic == null || value == null) {
                if (responseNeeded && device != null) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            if (characteristic.uuid != BleProtocol.MARK_UUID) {
                if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }

            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            // первый байт: 0x01 — «продолжение», 0x00 — «конец сообщения»
            val hasMore = value.isNotEmpty() && value[0].toInt() == 0x01
            val chunk = if (value.isEmpty()) "" else String(value, 1, value.size - 1, Charsets.UTF_8)

            val buffer = writeBuffers.getOrPut(device.address) { StringBuilder() }
            if (buffer.length + chunk.length > MAX_MARK_SIZE) {
                writeBuffers.remove(device.address)
                sendAck(device, BleProtocol.ACK_BAD_TOKEN, "Слишком большой запрос")
                return
            }
            buffer.append(chunk)

            if (!hasMore) {
                val json = writeBuffers.remove(device.address)?.toString().orEmpty()
                handleMark(device, json)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            Log.d(TAG, "ACK отправлен ${device?.address} (статус $status)")
        }
    }

    // ------------------------------------------------------------- приём отметки

    /** Студент приложил телефон: разбираем JSON и записываем касание. */
    private fun handleMark(device: BluetoothDevice, json: String) {
        val session = TerminalState.activeSession
        if (!TerminalState.emulating || session == null) {
            sendAck(device, BleProtocol.ACK_NOT_EMULATING, "Перекличка не идёт")
            return
        }

        val payload = runCatching { JSONObject(json) }.getOrNull()
        if (payload == null) {
            sendAck(device, BleProtocol.ACK_BAD_TOKEN, "Некорректный запрос")
            return
        }

        val studentDeviceId = payload.optString("device")
        val tokenHex = payload.optString("token")
        val rssi = if (payload.has("rssi")) payload.optInt("rssi") else null
        val studentName = payload.optString("name").ifBlank { studentDeviceId }
        val clientTime = payload.optString("time")

        val token = BleProtocol.tokenFromHex(tokenHex)
        if (token == null || !BleProtocol.tokenMatches(tokenSecret, token)) {
            Log.w(TAG, "Токен не совпал — отметка отклонена ($studentDeviceId)")
            TerminalState.bleStatus = "Отклонено: устаревший токен (${studentName})"
            sendAck(device, BleProtocol.ACK_BAD_TOKEN, "Метка устарела, поднесите телефон ещё раз")
            return
        }

        val now = System.currentTimeMillis()
        val previous = lastMarkAt[device.address] ?: 0L
        if (now - previous < MARK_COOLDOWN_MS) {
            sendAck(device, BleProtocol.ACK_RATE_LIMITED, "Слишком часто — подождите пару секунд")
            return
        }
        lastMarkAt[device.address] = now

        val tapTime = TerminalState.nowIso()
        TerminalState.recordTap(
            context = applicationContext,
            sessionId = session.id,
            tapTime = tapTime,
            result = "ble",
            deviceId = studentDeviceId,
            rssi = rssi,
            studentName = studentName,
        )

        Log.i(TAG, "Касание: $studentName ($studentDeviceId), RSSI ${rssi ?: "?"}, время клиента $clientTime")
        TerminalState.bleStatus = "Отметился: $studentName (${rssi?.let { "$it dBm" } ?: "RSSI неизвестен"})"
        sendAck(device, BleProtocol.ACK_OK, "Отметка принята", session.id)
    }

    private fun sendAck(device: BluetoothDevice, code: Int, message: String, sessionId: String? = null) {
        val server = gattServer ?: return
        val characteristic = ackCharacteristic ?: return
        if (notifyEnabled[device.address] != true) {
            Log.d(TAG, "ACK не отправлен (уведомления выключены): $message")
            return
        }
        characteristic.value = BleProtocol.ackJson(code, message, sessionId).toByteArray()
        runCatching { server.notifyCharacteristicChanged(device, characteristic, false) }
    }

    // ---------------------------------------------------------------- данные

    /** Ответ на INFO: данные пары и порог сигнала, выставленный преподавателем. */
    private fun infoJson(): String {
        val session = TerminalState.activeSession
        return JSONObject().apply {
            put("app", "visits11-terminal")
            put("session_id", session?.id ?: "")
            put("subject", session?.subject ?: "")
            put("teacher_id", session?.teacherId ?: "")
            put("group", session?.groupName ?: "")
            put("min_rssi", TerminalState.range.minRssi)
            put("range", TerminalState.range.name)
            put("token_ttl", BleProtocol.TOKEN_TTL_SECONDS)
        }.toString()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.ble_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = getString(R.string.ble_channel_desc) },
                )
            }
        }

        val session = TerminalState.activeSession
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ru.kubgau.attendance.terminal.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(getString(R.string.ble_notification_title))
            .setContentText(
                session?.let { "${it.subject} · ${it.groupName ?: "без группы"}" }
                    ?: getString(R.string.ble_notification_text),
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "BleTerminalService"
        private const val CHANNEL_ID = "terminal_ble"
        private const val NOTIFICATION_ID = 1101
        private const val MAX_MARK_SIZE = 2048
        private const val MARK_COOLDOWN_MS = 5_000L

        /** Запустить BLE-метку (вызывается из экрана преподавателя). */
        fun start(context: Context) {
            val intent = Intent(context, BleTerminalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        /** Остановить BLE-метку. */
        fun stop(context: Context) {
            context.stopService(Intent(context, BleTerminalService::class.java))
            TerminalState.bleActive = false
            TerminalState.bleStatus = "BLE-метка выключена"
        }
    }
}
