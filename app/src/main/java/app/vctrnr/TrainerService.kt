package app.vctrnr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Keeps vctrnr running with the screen off (phone in your pocket) from the moment you open
 * it until you close it. Android requires a visible notification for that; here it's the
 * media player in the notification shade and on the lock screen, with start/stop and Close.
 *
 * Closing: the Close button (in the app, the notification or the lock-screen player), or
 * swiping vctrnr away from Recents. After that nothing of vctrnr runs.
 */
class TrainerService : Service() {

    inner class LocalBinder : Binder() {
        val service: TrainerService get() = this@TrainerService
    }

    lateinit var controller: TrainerController
        private set

    private val binder = LocalBinder()
    private lateinit var notifications: NotificationManager
    private var closed = false

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Training session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while vctrnr is open, with start/stop and close"
                setShowBadge(false)
            },
        )
        controller = TrainerController(this, onCloseRequested = ::shutdown)
        controller.onSummaryChanged = ::refreshNotification
        controller.open()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggle()
            ACTION_CLOSE -> shutdown()
            else -> {
                // Opened from the app (startForegroundService): must be foreground right away.
                if (closed) {
                    closed = false
                    controller.open()
                }
                goForeground()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Swiped away from Recents = closed. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (!closed) controller.close()
        super.onDestroy()
    }

    /**
     * (Re)declares the foreground service. Only call while the app is on screen: Android only
     * lets an app start using the microphone in the background if it was allowed to while
     * visible. Called again after the mic permission is granted, to add the mic type.
     */
    fun goForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (controller.hasMicPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val notification = buildNotification()
        try {
            // phoneCall: keeps the earbud-mic "call" healthy with the screen off.
            startForeground(NOTIFICATION_ID, notification, types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (e: SecurityException) {
            Log.w("vctrnr.Service", "phoneCall service type refused; continuing without it", e)
            startForeground(NOTIFICATION_ID, notification, types)
        }
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        controller.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification() {
        if (!closed) notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val running = controller.state.value.running
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("vctrnr")
            .setContentText(controller.summary())
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 12 shows these buttons; 13+ builds the player from the media session.
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (running) R.drawable.ic_stop else R.drawable.ic_mic),
                    if (running) "Stop" else "Start",
                    serviceIntent(ACTION_TOGGLE, 1),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "Close", serviceIntent(ACTION_CLOSE, 2)).build(),
            )
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        controller.sessionToken?.let { style.setMediaSession(it) }
        return builder.setStyle(style).build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, TrainerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "app.vctrnr.action.TOGGLE"
        const val ACTION_CLOSE = "app.vctrnr.action.CLOSE"
    }
}
