package ru.kubgau.attendance.terminal.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Запасной способ отметки — QR-код.
 *
 * Внутри QR лежит ровно тот же JSON, что и в NFC-метке:
 *   {"session_id":"…","subject":"…","teacher_id":"…","timestamp":"…"}
 *
 * Зачем нужен: NFC-чтение на iPhone требует платного аккаунта разработчика
 * Apple (entitlement «NFC Tag Reading»). QR-код читает обычная камера —
 * такой вариант работает и с бесплатной подписью.
 */
object QrCodeEncoder {

    /** Рисует QR с полезной нагрузкой [payload] в квадрат [sizePx]. */
    fun encode(payload: String, sizePx: Int = 800): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }
}
