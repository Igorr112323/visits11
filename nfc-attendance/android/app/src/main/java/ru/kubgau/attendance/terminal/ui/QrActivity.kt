package ru.kubgau.attendance.terminal.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.kubgau.attendance.terminal.databinding.ActivityQrBinding
import ru.kubgau.attendance.terminal.nfc.NdefMessageBuilder
import ru.kubgau.attendance.terminal.nfc.TerminalState

/**
 * Экран «Покажите студентам»: крупный QR-код текущей пары.
 *
 * Код обновляется каждые 5 секунд — с новым timestamp. Это важно:
 *   • скриншот быстро «стареет» и не годится для отметки;
 *   • каждое обновление регистрирует касание на стороне преподавателя,
 *     поэтому отметки студентов по QR сервер считает подтверждёнными
 *     (студент физически видел экран преподавателя).
 */
class QrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrBinding
    private var lastTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = TerminalState.activeSession
        if (session == null) {
            binding.qrTitle.text = "Пара не создана"
            binding.qrHint.text = "Вернитесь назад и создайте пару"
            return
        }

        binding.qrTitle.text = session.subject
        binding.qrHint.text = buildString {
            append(session.groupName ?: "группа не указана")
            append("\nКод обновляется каждые 5 секунд")
        }

        lifecycleScope.launch {
            while (true) {
                val timestamp = TerminalState.nowIso()
                val payload = NdefMessageBuilder.payloadJson(
                    sessionId = session.id,
                    subject = session.subject,
                    teacherId = session.teacherId,
                    timestamp = timestamp,
                )
                binding.qrImage.setImageBitmap(QrCodeEncoder.encode(payload, 800))
                binding.qrTime.text = timestamp.substringAfter('T')

                // «касание»: код показан — студент видит экран преподавателя
                val now = System.currentTimeMillis()
                if (now - lastTapAt > 10_000) {
                    lastTapAt = now
                    TerminalState.recordTap(applicationContext, session.id, timestamp, result = "qr")
                }

                delay(5_000)
            }
        }
    }

    override fun onDestroy() {
        binding.qrImage.setImageBitmap(null)
        super.onDestroy()
    }
}
