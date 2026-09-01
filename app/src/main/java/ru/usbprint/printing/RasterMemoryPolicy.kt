package ru.usbprint.printing

import ru.usbprint.domain.model.RasterDimensionLimits

/** Single conservative policy used before raster allocations or page rendering. */
object RasterMemoryPolicy {
    const val MAX_PAGE_DIMENSION_PX = RasterDimensionLimits.MAX_PAGE_DIMENSION_PX
    /** Allows streamed A4 at 600 DPI (about 34.8 MP) while still rejecting larger unsafe combinations. */
    const val MAX_PAGE_PIXELS = RasterDimensionLimits.MAX_PAGE_PIXELS
    const val MAX_SOURCE_PIXELS = 14_000_000L
    const val MAX_SOURCE_METADATA_PIXELS = 500_000_000L
    const val MAX_RENDER_WIDTH = 2_048

    fun requireSafePage(width: Int, height: Int) {
        RasterDimensionLimits.requireSafePage(width, height)
    }

    fun requireSafeSourceMetadata(width: Int, height: Int) {
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixels <= MAX_SOURCE_METADATA_PIXELS) { "Source dimensions exceed the safe metadata limit" }
    }

    fun estimatedWorkingBytes(width: Int, height: Int, bytesPerPixel: Int): Long {
        require(width > 0 && height > 0 && bytesPerPixel > 0)
        return try {
            val pixels = Math.multiplyExact(width.toLong(), height.toLong())
            val raster = Math.multiplyExact(pixels, bytesPerPixel.toLong())
            Math.addExact(raster, Math.multiplyExact(width.toLong(), 8L))
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("Raster byte calculation overflow", overflow)
        }
    }
}
