package ru.usbprint.printing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Generates a local A4 calibration PDF; it does not mark a printer as verified. */
class HardwareTestPageFactory(private val context: Context) {
    fun create(): android.net.Uri {
        val file = File(context.cacheDir, "usb-print-hardware-test-a4.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create())
            draw(page.canvas)
            document.finishPage(page)
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun draw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        text.textSize = 18f; text.isFakeBoldText = true
        canvas.drawText("USB Print — Hardware Test / A4", 36f, 48f, text)
        text.textSize = 10f; text.isFakeBoldText = false
        canvas.drawText("Сверьте рамку, поля, градации серого, цветовые блоки и мелкий текст. SENT не означает успешную печать.", 36f, 68f, text)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = Color.BLACK }
        canvas.drawRect(18f, 18f, A4_WIDTH_PT - 18f, A4_HEIGHT_PT - 18f, line)
        canvas.drawRect(36f, 92f, A4_WIDTH_PT - 36f, A4_HEIGHT_PT - 72f, line)
        repeat(10) { index ->
            val gray = index * 255 / 9
            val paint = Paint().apply { color = Color.rgb(gray, gray, gray) }
            canvas.drawRect(40f + index * 51f, 112f, 40f + (index + 1) * 51f, 152f, paint)
        }
        val colors = listOf(Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE)
        colors.forEachIndexed { index, color ->
            canvas.drawRect(40f + index * 72f, 170f, 104f + index * 72f, 216f, Paint().apply { this.color = color })
        }
        text.textSize = 7f
        (0..20).forEach { i ->
            val y = 250f + i * 22f
            canvas.drawLine(40f, y, A4_WIDTH_PT - 40f, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.4f })
            canvas.drawText("${i + 4} pt: The quick brown fox 0123456789 — Кириллица: Проверка печати", 44f, y - 4f, text)
        }
        text.textSize = 9f
        canvas.drawText("Дата создания: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}", 36f, A4_HEIGHT_PT - 46f, text)
    }

    private companion object { const val A4_WIDTH_PT = 595; const val A4_HEIGHT_PT = 842 }
}
