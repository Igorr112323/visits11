package ru.kubgau.attendance.terminal.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.R
import ru.kubgau.attendance.terminal.ble.BleRange
import ru.kubgau.attendance.terminal.databinding.ActivityMainBinding

/**
 * Экран преподавателя:
 *   • адрес локального сервера и проверка связи;
 *   • создание пары (предмет, группа, ваш id, длительность);
 *   • кнопка «Начать/остановить эмуляцию» — телефон превращается в NFC-метку;
 *   • список тех, кто уже отметился (данные сервера).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = PresentAdapter()

    /** Разрешения Bluetooth (Android 12+) и уведомлений (Android 13+). */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val bluetoothGranted = results[Manifest.permission.BLUETOOTH_CONNECT] != false &&
            results[Manifest.permission.BLUETOOTH_ADVERTISE] != false
        if (bluetoothGranted) {
            viewModel.onPermissionsGranted()
        } else {
            Toast.makeText(this, R.string.permission_bluetooth, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.presentList.layoutManager = LinearLayoutManager(this)
        binding.presentList.adapter = adapter

        bindInputs()
        bindButtons()
        observeState()
        showNfcStatus()
        showBluetoothStatus()
        requestNeededPermissions()
    }

    /** Bluetooth-разрешения нужны только с Android 12; на старых версиях их нет. */
    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .forEach { permission ->
                    if (ContextCompat.checkSelfPermission(this, permission) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        needed += permission
                    }
                }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.onPermissionsGranted()
        }
    }

    private fun showBluetoothStatus() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val enabled = adapter?.isEnabled == true
        binding.bleDot.setBackgroundResource(
            if (enabled) R.drawable.dot_green else R.drawable.dot_red,
        )
        if (!enabled) {
            Toast.makeText(this, "Включите Bluetooth — по нему принимаются касания", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        showNfcStatus()
    }

    // ------------------------------------------------------------------- ввод

    private fun bindInputs() {
        binding.serverUrl.inputType = InputType.TYPE_TEXT_VARIATION_URI
        binding.minutes.inputType = InputType.TYPE_CLASS_NUMBER

        binding.serverUrl.doAfterTextChanged { viewModel.onServerUrlChanged(it?.toString().orEmpty()) }
        binding.subject.doAfterTextChanged { viewModel.onSubjectChanged(it?.toString().orEmpty()) }
        binding.group.doAfterTextChanged { viewModel.onGroupChanged(it?.toString().orEmpty()) }
        binding.teacherId.doAfterTextChanged { viewModel.onTeacherChanged(it?.toString().orEmpty()) }
        binding.minutes.doAfterTextChanged { viewModel.onMinutesChanged(it?.toString().orEmpty()) }
    }

    private fun bindButtons() {
        binding.checkServer.setOnClickListener {
            lifecycleScope.launch { viewModel.checkServer() }
        }
        binding.saveServer.setOnClickListener { viewModel.saveServerUrl() }
        binding.createSession.setOnClickListener { viewModel.createSession() }
        binding.toggleEmulation.setOnClickListener { viewModel.toggleEmulation() }
        binding.syncNow.setOnClickListener { viewModel.syncNow() }

        // Насколько близко прикладывать телефон (влияет на мощность BLE-вещания)
        binding.rangeGroup.setOnCheckedChangeListener { _, checkedId ->
            val range = when (checkedId) {
                R.id.rangeNear -> BleRange.NEAR
                R.id.rangeWide -> BleRange.WIDE
                else -> BleRange.TOUCH
            }
            viewModel.onRangeChanged(range)
        }
        binding.refreshPresent.setOnClickListener { viewModel.refreshPresentNow() }
        binding.closeSession.setOnClickListener { viewModel.closeSession() }
    }

    // -------------------------------------------------------------- состояние

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // адрес сервера показываем только если поле не редактируют
                    if (binding.serverUrl.text?.toString() != state.serverUrl) {
                        binding.serverUrl.setText(state.serverUrl)
                    }

                    binding.serverStatus.text = when (state.serverOnline) {
                        true -> getString(R.string.status_online)
                        false -> getString(R.string.status_offline)
                        null -> "Связь не проверена"
                    }
                    binding.serverDot.setBackgroundResource(
                        when (state.serverOnline) {
                            true -> R.drawable.dot_green
                            false -> R.drawable.dot_red
                            null -> R.drawable.dot_gray
                        },
                    )

                    val session = state.session
                    binding.currentCard.visibility = if (session == null) View.GONE else View.VISIBLE
                    binding.noSession.visibility = if (session == null) View.VISIBLE else View.GONE

                    if (session != null) {
                        binding.currentSubject.text = session.subject
                        binding.currentGroup.text = session.groupName ?: "группа не указана"
                        binding.currentSessionId.text = session.id
                        binding.currentElapsed.text = state.elapsed
                        binding.tapCount.text = state.tapCount.toString()
                        binding.emulationStatus.text = if (state.emulating) {
                            getString(R.string.status_emulating)
                        } else {
                            getString(R.string.status_idle)
                        }
                        binding.emulationDot.setBackgroundResource(
                            if (state.emulating) R.drawable.dot_green else R.drawable.dot_gray,
                        )
                        binding.toggleEmulation.text = if (state.emulating) {
                            getString(R.string.button_stop_emulation)
                        } else {
                            getString(R.string.button_start_emulation)
                        }
                    }

                    binding.rangeHint.text = when (state.range) {
                        BleRange.TOUCH -> getString(R.string.range_hint_touch)
                        BleRange.NEAR -> getString(R.string.range_hint_near)
                        BleRange.WIDE -> getString(R.string.range_hint_wide)
                    }
                    when (state.range) {
                        BleRange.TOUCH -> binding.rangeTouch.isChecked = true
                        BleRange.NEAR -> binding.rangeNear.isChecked = true
                        BleRange.WIDE -> binding.rangeWide.isChecked = true
                    }

                    binding.bleStatus.text = state.bleStatus
                    binding.bleDot.setBackgroundResource(
                        if (state.bleActive) R.drawable.dot_green else R.drawable.dot_red,
                    )
                    binding.lastStudent.text = state.lastStudent.ifBlank {
                        getString(R.string.ble_last_none)
                    }

                    binding.presentCount.text = state.presentCount.toString()
                    adapter.submit(state.present)

                    binding.message.text = state.message
                    binding.message.visibility = if (state.message.isBlank()) View.GONE else View.VISIBLE
                    binding.syncState.text = state.syncState
                    binding.syncState.visibility =
                        if (state.syncState.isBlank()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun showNfcStatus() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        val text = when {
            adapter == null -> "NFC в телефоне не поддерживается"
            !adapter.isEnabled -> "NFC выключен — включите его в настройках"
            else -> "NFC включён, HCE-метка готова"
        }
        binding.nfcStatus.text = text
        binding.nfcDot.setBackgroundResource(
            if (adapter?.isEnabled == true) R.drawable.dot_green else R.drawable.dot_red,
        )
        if (adapter != null && !adapter.isEnabled) {
            Toast.makeText(this, "Для отметок включите NFC", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
