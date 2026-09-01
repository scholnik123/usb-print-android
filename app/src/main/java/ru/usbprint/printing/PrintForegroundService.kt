package ru.usbprint.printing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.usbprint.MainActivity
import ru.usbprint.UsbPrintApplication
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintJobStatus

/** Exists only while a real USB transfer is pending, keeping printing independent of Activity recreation. */
class PrintForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var observer: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as UsbPrintApplication).container
        when (intent?.action) {
            ACTION_CANCEL -> container.printExecutor.cancel()
            ACTION_START -> if (worker == null) {
                val job = container.printJobStore.take() ?: run { stopSelf(startId); return START_NOT_STICKY }
                createChannel()
                startForeground(NOTIFICATION_ID, notification("Подготовка задания", 0, true))
                observer = scope.launch {
                    container.printExecutor.state.collectLatest { state ->
                        val running = state.status !in terminalStates
                        val text = listOfNotNull(state.status.userLabel, state.progressDetail, state.detail).filter(String::isNotBlank).joinToString(" · ")
                        updateNotification(text, state.progress, running)
                    }
                }
                worker = scope.launch {
                    runCatching { container.printExecutor.execute(job) }
                    container.printJobStore.finish()
                    observer?.cancel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (worker?.isActive == true) (application as UsbPrintApplication).container.printExecutor.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB Print", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(text: String, progress: Int?, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text, progress, ongoing))
        }
    }

    private fun notification(text: String, progress: Int?, ongoing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent = PendingIntent.getService(this, 1, Intent(this, PrintForegroundService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("USB Print")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress?.coerceIn(0, 100) ?: 0, progress == null && ongoing)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "usb_print_jobs"
        const val NOTIFICATION_ID = 12
        const val ACTION_START = "ru.usbprint.action.START_PRINT"
        const val ACTION_CANCEL = "ru.usbprint.action.CANCEL_PRINT"
        val terminalStates = setOf(PrintJobStatus.IDLE, PrintJobStatus.SENT, PrintJobStatus.CANCELLED, PrintJobStatus.ERROR)

        fun start(context: Context, job: PrintJob): Boolean {
            val container = (context.applicationContext as UsbPrintApplication).container
            if (!container.printJobStore.enqueue(job)) return false
            val intent = Intent(context, PrintForegroundService::class.java).setAction(ACTION_START)
            return runCatching { context.startForegroundService(intent) }
                .onFailure { container.printJobStore.finish() }
                .isSuccess
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, PrintForegroundService::class.java).setAction(ACTION_CANCEL))
        }
    }
}

private val PrintJobStatus.userLabel: String get() = when (this) {
    PrintJobStatus.VALIDATING -> "Проверка задания"
    PrintJobStatus.OPENING_USB -> "Открытие USB-принтера"
    PrintJobStatus.PREPARING_DOCUMENT -> "Подготовка документа"
    PrintJobStatus.RENDERING -> "Рендеринг страницы"
    PrintJobStatus.GENERATING_PAYLOAD -> "Создание данных печати"
    PrintJobStatus.SENDING -> "Передача принтеру"
    PrintJobStatus.WAITING_STATUS -> "Проверка статуса"
    PrintJobStatus.SENT -> "Задание передано принтеру"
    PrintJobStatus.CANCELLED -> "Печать отменена"
    PrintJobStatus.ERROR -> "Ошибка печати"
    PrintJobStatus.IDLE -> ""
}
