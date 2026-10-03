package ru.kubgau.attendance.terminal.ui

import android.nfc.NfcAdapter
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.R
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
        binding.showQr.setOnClickListener {
            // QR-код читают те, у кого нет NFC: iPhone, кнопочные телефоны и т.п.
            startActivity(android.content.Intent(this, QrActivity::class.java))
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
