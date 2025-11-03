package com.boyai.mqtt

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import info.mqtt.android.service.MqttAndroidClient
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*

class MqttForegroundService : Service() {

    private lateinit var client: MqttAndroidClient
    private var serverUri: String = ""
    private var username: String = ""
    private var password: String = ""
    private var topic: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "MqttChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val ACTION_REQUEST_STATUS = "ACTION_REQUEST_STATUS"

        fun start(context: Context) {
            if (!isServiceRunning(context)) {
                val intent = Intent(context, MqttForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, MqttForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestStatus(context: Context) {
            val intent = Intent(context, MqttForegroundService::class.java).apply {
                action = ACTION_REQUEST_STATUS
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun isServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (MqttForegroundService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        loadSettings()
        createNotificationChannel()

        // Always show foreground notification
        startForeground(NOTIFICATION_ID, createConnectingNotification())

        client = MqttAndroidClient(this, serverUri, "android_${System.currentTimeMillis()}")

        client.setCallback(object : MqttCallbackExtended {
            override fun connectionLost(cause: Throwable?) {
                Log.w("MqttService", "Connection lost", cause)
                broadcastStatus(false, cause?.message)
                updateNotificationText("Disconnected")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.toString() ?: return
                Log.d("MqttService", "Message arrived: $payload")
                showNotification(payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}

            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                Log.d("MqttService", "Connected successfully (reconnect=$reconnect)")
                updateNotificationText("Connected")
                subscribeToTopic()
                broadcastStatus(true)
            }
        })

        // Only connect if not already connected
        if (::client.isInitialized && !client.isConnected) {
            connect()
        }
    }

    private fun createConnectingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT Service")
            .setContentText("Connecting...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT Service")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("mqtt_settings", Context.MODE_PRIVATE)
        serverUri = prefs.getString("server_uri", "tcp://home.boyai.cc:1883") ?: "tcp://home.boyai.cc:1883"
        username = prefs.getString("username", "boyang") ?: "boyang"
        password = prefs.getString("password", "asdasdqwe") ?: "asdasdqwe"
        topic = prefs.getString("topic", "notifications/boyai") ?: "notifications/boyai"
        Log.d("MqttService", "Loaded settings - User: $username, Topic: $topic")
    }

    private fun connect() {
        if (::client.isInitialized && client.isConnected) return

        val safePassword = password.ifBlank { "asdasdqwe" }

        val options = MqttConnectOptions().apply {
            userName = username
            password = safePassword.toCharArray()
            isAutomaticReconnect = true
            isCleanSession = false
            connectionTimeout = 10
        }

        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d("MqttService", "Connection initiated successfully")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e("MqttService", "Connection failed", exception)
                    broadcastStatus(false, exception?.message ?: "Connect failed")
                    updateNotificationText("Connect Failed")
                    scope.launch {
                        delay(5000)
                        connect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MqttService", "Connect error", e)
            broadcastStatus(false, e.message ?: "Exception during connect")
            updateNotificationText("Error: ${e.javaClass.simpleName}")
            scope.launch {
                delay(5000)
                connect()
            }
        }
    }

    private fun subscribeToTopic() {
        if (!::client.isInitialized || !client.isConnected) return

        try {
            client.subscribe(topic, 0, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d("MqttService", "Subscribed to $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e("MqttService", "Subscribe failed", exception)
                    scope.launch {
                        delay(5000)
                        subscribeToTopic()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MqttService", "Subscribe error", e)
            scope.launch {
                delay(5000)
                subscribeToTopic()
            }
        }
    }

    private fun showNotification(text: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val notificationId = System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT Alert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_LIGHTS) // No DEFAULT_SOUND

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MQTT Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming MQTT alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                enableLights(true)
                setSound(null, null) // ✅ Safe for Xiaomi — no custom sound
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun broadcastStatus(connected: Boolean, error: String? = null) {
        val intent = Intent("MQTT_STATUS_UPDATE").apply {
            putExtra("connected", connected)
            error?.let { putExtra("error", it) }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                Log.d("MqttService", "Received ACTION_DISCONNECT")
                disconnectAndStop()
            }
            ACTION_REQUEST_STATUS -> {
                val isConnected = ::client.isInitialized && client.isConnected
                broadcastStatus(isConnected)
            }
        }
        return START_STICKY
    }

    private fun disconnectAndStop() {
        try {
            if (::client.isInitialized && client.isConnected) {
                client.disconnect(null, object : IMqttActionListener {
                    override fun onSuccess(token: IMqttToken?) { stopSelfSafely() }
                    override fun onFailure(token: IMqttToken?, exception: Throwable?) { stopSelfSafely() }
                })
            } else {
                stopSelfSafely()
            }
        } catch (e: Exception) {
            Log.e("MqttService", "Error during disconnect", e)
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        updateNotificationText("Disconnected")
        scope.launch(Dispatchers.Main) {
            delay(300)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        try {
            if (::client.isInitialized) {
                client.close()
            }
        } catch (e: Exception) {
            Log.e("MqttService", "Error closing client", e)
        }
        super.onDestroy()
        broadcastStatus(false, "Service destroyed")
    }
}
