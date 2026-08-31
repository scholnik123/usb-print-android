package ru.usbprint.domain.logic

import ru.usbprint.domain.model.PageSelection

/** Parses a 1-based user page range such as `1-4, 7, 9-12`. */
object PageRangeParser {
    fun parse(input: String, pageCount: Int? = null): Result<List<IntRange>> = runCatching {
        require(input.isNotBlank()) { "Пустой диапазон" }
        val ranges = input.split(',').map { token ->
            val value = token.trim()
            require(value.isNotEmpty()) { "Пустой элемент" }
            val parts = value.split('-', limit = 2).map { it.trim() }
            val start = parts[0].toIntOrNull() ?: error("Некорректная страница")
            val end = if (parts.size == 2) parts[1].toIntOrNull() ?: error("Некорректная страница") else start
            require(start >= 1 && end >= start) { "Некорректный диапазон" }
            if (pageCount != null) require(end <= pageCount) { "Страница вне документа" }
            start..end
        }
        ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { result, range ->
            val last = result.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, range.last)
            } else result += range
            result
        }
    }

    fun expand(selection: PageSelection, pageCount: Int, currentPage: Int = 1): List<Int> = when (selection) {
        PageSelection.All -> (1..pageCount).toList()
        PageSelection.Current -> listOf(currentPage.coerceIn(1, pageCount))
        PageSelection.Odd -> (1..pageCount).filter { it % 2 == 1 }
        PageSelection.Even -> (1..pageCount).filter { it % 2 == 0 }
        is PageSelection.Ranges -> selection.pages.flatMap { it.toList() }.distinct().filter { it in 1..pageCount }
    }
}
