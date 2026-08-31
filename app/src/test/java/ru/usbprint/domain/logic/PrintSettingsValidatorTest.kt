package ru.usbprint.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PrintSettings

class PrintSettingsValidatorTest {
    @Test fun acceptsSaneSettings() { assertNull(PrintSettingsValidator.validate(PrintSettings(copies = 2), 8)) }
    @Test fun rejectsInvalidCopiesAndRanges() {
        assertEquals("Количество копий должно быть от 1 до 99.", PrintSettingsValidator.validate(PrintSettings(copies = 0), 8))
        assertEquals("Диапазон страниц выходит за пределы документа.", PrintSettingsValidator.validate(PrintSettings(pageSelection = PageSelection.Ranges("9", listOf(9..9))), 8))
    }
}
