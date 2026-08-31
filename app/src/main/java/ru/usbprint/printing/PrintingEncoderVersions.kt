package ru.usbprint.printing

import ru.usbprint.domain.model.BackendId

/** Versions change only when emitted bytes or interpretation require a new physical check. */
object PrintingEncoderVersions {
    val current: Map<BackendId, Int> = BackendId.entries.mapNotNull { backend ->
        forBackend(backend)?.let { backend to it }
    }.toMap()

    fun forBackend(backend: BackendId): Int? = when (backend) {
        BackendId.IPP_DIRECT, BackendId.PDF_DIRECT, BackendId.ESC_POS, BackendId.RAW -> 1
        BackendId.IPP_PWG, BackendId.PWG_RASTER -> PwgRasterEncoder.ENCODER_VERSION
        BackendId.POSTSCRIPT_RASTER -> PostScriptRasterEncoder.ENCODER_VERSION
        BackendId.PCL5_RASTER -> Pcl5JobEncoder.ENCODER_VERSION
        BackendId.NONE -> null
    }
}
