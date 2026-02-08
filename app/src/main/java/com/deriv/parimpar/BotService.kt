package com.deriv.parimpar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BotService : Service() {

    private val binder = LocalBinder()
    val engine = TradingEngine()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    inner class LocalBinder : Binder() {
        fun getService(): BotService = this@BotService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Bot Iniciado")
        startForeground(1, notification)
        
        // Monitorar logs para atualizar notificação com lucro, se quiser
        return START_STICKY
    }

    fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun createNotification(contentStart: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Par Impar Decoder")
            .setContentText(contentStart)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Certifique-se que o icone existe, senão use android.R.drawable.ic_menu_info_details
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) 
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bot Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "BotServiceChannel"
    }
}
