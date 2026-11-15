package com.example.project1

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 글자(정산표 + 송금 링크)를 QR 그림으로 바꾼다 */
fun makeQr(text: String, size: Int = 480): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",   // 한글 깨지지 않게
        EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp.asImageBitmap()
}
