package ru.usbprint.printing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.ipp.IppClient
import ru.usbprint.ipp.IppJobReference
import ru.usbprint.ipp.IppResponse

fun interface PwgSpoolProducer {
    suspend fun produce(writeBytes: suspend (ByteArray) -> Unit)
}

/** Generates PWG once into a bounded cache file, then submits its exact length through IPP Print-Job. */
class IppPwgJobPipeline(private val spoolManager: IppPwgSpoolManager) {
    suspend fun submit(
        client: IppClient,
        jobName: String,
        settings: PrintSettings,
        supportedAttributeNames: Set<String>,
        pageCount: Int,
        producer: PwgSpoolProducer,
        mediaColSupported: Set<String> = emptySet(),
        mediaColMargins: HardwareMarginsMm? = null,
        onSpoolReady: (documentLength: Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Pair<IppResponse, IppJobReference> {
        ensureNotCancelled(isCancelled)
        return spoolManager.create().use { spool ->
            spool.openOutputStream().buffered().use { output ->
                producer.produce { bytes ->
                    ensureNotCancelled(isCancelled)
                    output.write(bytes)
                }
            }
            ensureNotCancelled(isCancelled)
            val documentLength = spool.length
            if (documentLength <= 0L) throw PrintException(AppError.RENDER_ERROR)
            onSpoolReady(documentLength)
            spool.openInputStream().buffered().use { document ->
                client.printJob(
                    document = document,
                    documentLength = documentLength,
                    documentFormat = PWG_MIME,
                    jobName = jobName,
                    settings = settings,
                    supportedAttributeNames = supportedAttributeNames.intersect(PASSTHROUGH_JOB_ATTRIBUTES),
                    pageCount = pageCount,
                    mediaColSupported = mediaColSupported,
                    mediaColMargins = mediaColMargins,
                    isCancelled = isCancelled
                )
            }
        }
    }

    private suspend fun ensureNotCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw PrintException(AppError.PRINT_CANCELLED)
        currentCoroutineContext().ensureActive()
    }

    companion object {
        const val PWG_MIME = "image/pwg-raster"
        private val PASSTHROUGH_JOB_ATTRIBUTES = setOf("media-col", "media-source", "media-type", "output-bin")
    }
}
