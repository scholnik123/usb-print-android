package ru.usbprint.document

import android.graphics.Bitmap

interface DocumentRenderer : AutoCloseable {
    val pageCount: Int
    fun pageSize(pageIndex: Int): Pair<Int, Int>
    fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap
    override fun close()
}
