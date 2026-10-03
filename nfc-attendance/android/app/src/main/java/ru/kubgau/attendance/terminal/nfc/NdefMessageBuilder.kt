package ru.kubgau.attendance.terminal.nfc

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Сборка NDEF-сообщения — ровно то, что прочитает iPhone.
 *
 * Запись: TNF = 2 (MIME), тип `application/vnd.kubgau.attendance+json`,
 * полезная нагрузка — JSON:
 *
 *   {"session_id":"…UUID…","subject":"Информатика",
 *    "teacher_id":"IVANOV_II","timestamp":"2026-10-04T10:15:00+03:00"}
 *
 * Короткая запись (SR) используется, пока payload < 256 байт; дальше — обычная
 * запись с 4-байтовой длиной. Так сообщение корректно читается и CoreNFC, и Android.
 */
object NdefMessageBuilder {

    const val MIME_TYPE = "application/vnd.kubgau.attendance+json"

    /** Максимальный размер области NDEF в эмулируемом файле (см. файл CC). */
    const val MAX_NDEF_FILE_SIZE = 1024

    private const val TNF_MIME = 0x02
    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_SR = 0x10

    /** NDEF-сообщение (последовательность записей). */
    fun buildMessage(jsonPayload: String): ByteArray {
        val type = MIME_TYPE.toByteArray(StandardCharsets.US_ASCII)
        val payload = jsonPayload.toByteArray(StandardCharsets.UTF_8)
        check(type.size <= 255) { "Слишком длинный MIME-тип" }

        val out = ByteArrayOutputStream()
        val shortRecord = payload.size < 256

        var header = TNF_MIME or FLAG_MB or FLAG_ME
        if (shortRecord) header = header or FLAG_SR

        out.write(header)
        out.write(type.size)
        if (shortRecord) {
            out.write(payload.size)
        } else {
            out.write((payload.size ushr 24) and 0xFF)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
        }
        out.write(type)
        out.write(payload)

        val message = out.toByteArray()
        check(message.size + 2 <= MAX_NDEF_FILE_SIZE) {
            "NDEF-сообщение не влезает в метку: ${message.size} байт"
        }
        return message
    }

    /**
     * Файл NDEF по правилам NFC Forum: 2 байта длины сообщения (NLEN), затем само сообщение.
     */
    fun buildNdefFile(jsonPayload: String): ByteArray {
        val message = buildMessage(jsonPayload)
        val file = ByteArray(message.size + 2)
        file[0] = ((message.size shr 8) and 0xFF).toByte()
        file[1] = (message.size and 0xFF).toByte()
        message.copyInto(file, destinationOffset = 2)
        return file
    }

    /** JSON-полезная нагрузка метки. */
    fun payloadJson(
        sessionId: String,
        subject: String,
        teacherId: String,
        timestamp: String,
    ): String = buildString {
        append('{')
        append("\"session_id\":\"").append(escape(sessionId)).append("\",")
        append("\"subject\":\"").append(escape(subject)).append("\",")
        append("\"teacher_id\":\"").append(escape(teacherId)).append("\",")
        append("\"timestamp\":\"").append(escape(timestamp)).append('"')
        append('}')
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
