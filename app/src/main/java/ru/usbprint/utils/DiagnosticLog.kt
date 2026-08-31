package ru.usbprint.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date

class DiagnosticLog(private val maxEntries: Int = 200) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries = _entries.asStateFlow()

    fun add(message: String) {
        val timestamp = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        _entries.value = (_entries.value + "$timestamp  $message").takeLast(maxEntries)
    }
}
