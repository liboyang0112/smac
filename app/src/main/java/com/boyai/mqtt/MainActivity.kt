package com.boyai.mqtt
import com.boyai.mqtt.databinding.ActivityMainBinding
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.textfield.TextInputEditText
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedSettings()

        binding.buttonConnect.setOnClickListener {
            if (binding.buttonConnect.text == "Disconnect") {
                MqttForegroundService.disconnect(this)
            } else {
                saveSettingsAndConnect()
            }
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(mqttStatusReceiver, IntentFilter("MQTT_STATUS_UPDATE"))
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("mqtt_settings", Context.MODE_PRIVATE)
        binding.editTextServerUri.setText(prefs.getString("server_uri", "tcp://your.server:1883"))
        binding.editTextUsername.setText(prefs.getString("username", "user"))
        binding.editTextPassword.setText(prefs.getString("password", "password"))
        binding.editTextTopic.setText(prefs.getString("topic", "notifications/#"))
        Log.d("MainActivity", "Settings loaded")
    }

    private fun saveSettingsAndConnect() {
        val serverUri = binding.editTextServerUri.text.toString().trim()
        val username = binding.editTextUsername.text.toString().trim()
        val password = binding.editTextPassword.text.toString()
        val topic = binding.editTextTopic.text.toString().trim()

        if (serverUri.isEmpty() || username.isEmpty() || topic.isEmpty()) {
            binding.textViewStatus.text = "Error: Fill all required fields"
            return
        }

        val success = getSharedPreferences("mqtt_settings", Context.MODE_PRIVATE).edit()
            .putString("server_uri", serverUri)
            .putString("username", username)
            .putString("password", password)
            .putString("topic", topic)
            .commit() // Sync write

        if (success) {
            Log.d("MainActivity", "Settings saved successfully")
            MqttForegroundService.start(this)
        } else {
            Log.e("MainActivity", "Failed to save settings!")
            binding.textViewStatus.text = "Error: Failed to save settings"
        }
    }

    private val mqttStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = intent?.getBooleanExtra("connected", false) ?: false
            val error = intent?.getStringExtra("error")

            binding.buttonConnect.text = if (connected) "Disconnect" else "Connect"
            binding.textViewStatus.text = when {
                connected -> "Status: Connected"
                !error.isNullOrEmpty() -> "Error: $error"
                else -> "Status: Disconnected"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(mqttStatusReceiver, IntentFilter("MQTT_STATUS_UPDATE"))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mqttStatusReceiver)
    }
}
