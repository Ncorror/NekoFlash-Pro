package io.github.ncorror.nekoflash.operation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.ncorror.nekoflash.MainActivity
import io.github.ncorror.nekoflash.NekoFlashApplication
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.operation.OperationRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Держит длительную операцию, пока экрана может не быть.
 *
 * `06` §1 требует, чтобы физическое время жизни передачи не принадлежало
 * Activity или ViewModel: пользователь вправе свернуть приложение посреди
 * прошивки, и операция от этого прерваться не должна. Тип
 * `connectedDevice` — тот, который платформа заводила ровно для этого случая.
 *
 * Сервис **ничего не выполняет сам**. Операцию ведут контроллеры на своих
 * потоках; сервис только не даёт системе убить процесс и показывает, что идёт.
 * Класть сюда работу значило бы завести второго владельца у того, у чего
 * владелец уже есть.
 *
 * Отмену уведомление предлагает **только там, где она честна** (`06` §10). У
 * NekoFlash это значит «до границы мутации»: после неё отменять нечего, и
 * кнопка была бы обещанием, которого никто не сдержит. Здесь кнопки нет вовсе —
 * отменяется операция на своём экране, где видно, что именно отменяется.
 */
public class OperationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notificationFor(emptyList()))
        watchOperations()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Следит за живыми операциями и **сам останавливается**, когда их нет.
     *
     * Сервис, переживший свою работу, — это уведомление, которое не уходит, и
     * процесс, который система держит зря.
     */
    private fun watchOperations() {
        val engine = (application as NekoFlashApplication).operations
        scope.launch {
            engine.live.collectLatest { live ->
                if (live.isEmpty()) {
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, notificationFor(live))
                }
            }
        }
    }

    private fun notificationFor(live: List<OperationRecord>): Notification {
        val shown = live.firstOrNull()
        val title = shown?.intent?.summary ?: getString(R.string.operation_notification_idle)
        val text = shown?.let { record -> line(record) } ?: getString(R.string.operation_notification_waiting)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    /**
     * Одна строка про операцию: цель, состояние и прогресс, если он есть.
     *
     * Процент показывается только когда известен объём: `null` там означает,
     * что неизвестно **сколько всего**, а не что ничего не сделано (`06` §10).
     */
    private fun line(record: OperationRecord): String {
        val percent = record.progress?.percent
        return if (percent == null) {
            getString(R.string.operation_notification_line, record.targetId.value, record.state.name)
        } else {
            getString(
                R.string.operation_notification_line_percent,
                record.targetId.value,
                record.state.name,
                percent,
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.operation_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.operation_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    public companion object {
        private const val CHANNEL_ID = "nekoflash.operations"
        private const val NOTIFICATION_ID = 1

        /**
         * Поднимает сервис, если он ещё не поднят.
         *
         * Вызывается в начале каждой длительной операции. Повторный вызов
         * безопасен: сервис один, и второй `startForegroundService` его не
         * дублирует.
         */
        public fun start(context: Context) {
            val intent = Intent(context, OperationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Тип, который платформа требует объявить и здесь, и в манифесте. */
        internal const val TYPE_CONNECTED_DEVICE: Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }
}
