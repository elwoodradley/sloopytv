package tv.sloopy.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun QrCode(text: String, size: androidx.compose.ui.unit.Dp) {
    val matrix = remember(text) {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 0,
        )
        com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
    }
    androidx.compose.foundation.Canvas(Modifier.width(size).height(size)) {
        val n = matrix.width
        val cell = this.size.width / n
        for (y in 0 until n) for (x in 0 until n) if (matrix[x, y]) {
            drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

