package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.custom.keyboard.R

/**
 * The switches on a Toggles tile. Each reads the phone's real state. Ones Android lets apps
 * change (torch, sound mode, Do Not Disturb, auto-rotate, auto-brightness) switch in place;
 * the rest (Wi-Fi, Bluetooth, location, aeroplane mode, mobile data) open the system panel,
 * because Android no longer lets apps flip them directly.
 */
class QuickToggles(private val context: Context, private val onChange: () -> Unit) {

    data class Toggle(val id: String, val label: String)

    /** Result of tapping a toggle: done in place, or an Intent the launcher should open. */
    sealed class Outcome {
        object Done : Outcome()
        class Open(val intent: Intent, val hint: String? = null) : Outcome()
    }

    companion object {
        val all = listOf(
            Toggle("torch", "Torch"),
            Toggle("wifi", "Wi-Fi"),
            Toggle("bluetooth", "Bluetooth"),
            Toggle("sound", "Sound"),
            Toggle("dnd", "Do not disturb"),
            Toggle("location", "Location"),
            Toggle("rotate", "Auto-rotate"),
            Toggle("brightness", "Auto brightness"),
            Toggle("data", "Mobile data"),
            Toggle("airplane", "Aeroplane")
        )
        val defaults = listOf("torch", "wifi", "bluetooth", "sound", "dnd", "location")
    }

    private val main = Handler(Looper.getMainLooper())
    private val cameras = context.getSystemService(CameraManager::class.java)
    private var torchId: String? = null
    var torchOn = false
        private set
    private var torchCallback: CameraManager.TorchCallback? = null

    init {
        torchId = try {
            cameras?.cameraIdList?.firstOrNull { id ->
                cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Start following the torch (only while Start is on screen). */
    fun start() {
        val cm = cameras ?: return
        if (torchCallback != null || torchId == null) return
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId && enabled != torchOn) {
                    torchOn = enabled
                    onChange()
                }
            }
        }
        torchCallback = cb
        try {
            cm.registerTorchCallback(cb, main)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        torchCallback?.let { runCatching { cameras?.unregisterTorchCallback(it) } }
        torchCallback = null
    }

    fun label(id: String): String = when (id) {
        "sound" -> when (ringerMode()) {
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            else -> "Sound"
        }
        else -> all.firstOrNull { it.id == id }?.label ?: id
    }

    fun icon(id: String): Int = when (id) {
        "torch" -> R.drawable.ic_m_torch
        "wifi" -> R.drawable.ic_m_wifi
        "bluetooth" -> R.drawable.ic_m_bluetooth
        "sound" -> when (ringerMode()) {
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_m_vibrate
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_m_mute
            else -> R.drawable.ic_m_volume
        }
        "dnd" -> R.drawable.ic_m_dnd
        "location" -> if (isOn("location")) R.drawable.ic_m_location else R.drawable.ic_m_location_off
        "rotate" -> R.drawable.ic_m_rotate
        "brightness" -> R.drawable.ic_m_brightness
        "data" -> R.drawable.ic_m_data
        "airplane" -> R.drawable.ic_m_airplane
        else -> R.drawable.ic_m_toggles
    }

    private fun ringerMode(): Int = context.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

    @SuppressLint("MissingPermission")
    fun isOn(id: String): Boolean = try {
        when (id) {
            "torch" -> torchOn
            "wifi" -> context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            "bluetooth" -> context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            "sound" -> ringerMode() == AudioManager.RINGER_MODE_NORMAL
            "dnd" -> (context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) > NotificationManager.INTERRUPTION_FILTER_ALL
            "location" -> context.getSystemService(LocationManager::class.java)?.let { lm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } == true
            "rotate" -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "brightness" -> Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            "airplane" -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "data" -> false
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    fun toggle(id: String): Outcome {
        val outcome = when (id) {
            "torch" -> {
                val camera = torchId
                if (camera == null) Outcome.Open(Intent(Settings.ACTION_SETTINGS), "This phone has no torch")
                else try {
                    cameras?.setTorchMode(camera, !torchOn)
                    torchOn = !torchOn
                    Outcome.Done
                } catch (_: Exception) {
                    Outcome.Open(Intent(Settings.ACTION_SETTINGS), "The camera is busy")
                }
            }
            "wifi" -> Outcome.Open(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS))
            "bluetooth" -> Outcome.Open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "data" -> Outcome.Open(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))
            "location" -> Outcome.Open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "airplane" -> Outcome.Open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            "sound" -> cycleSound()
            "dnd" -> toggleDnd()
            "rotate", "brightness" -> toggleSystemSetting(id)
            else -> Outcome.Done
        }
        if (outcome is Outcome.Done) onChange()
        return outcome
    }

    private fun policyAccess(): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun policyIntent() = Outcome.Open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Allow Do Not Disturb access for the launcher")

    /** Sound → vibrate → silent (silent only with Do Not Disturb access, as Android requires). */
    private fun cycleSound(): Outcome {
        val audio = context.getSystemService(AudioManager::class.java) ?: return Outcome.Done
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> if (policyAccess()) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return try {
            audio.ringerMode = next
            Outcome.Done
        } catch (_: SecurityException) {
            policyIntent()
        }
    }

    private fun toggleDnd(): Outcome {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return Outcome.Done
        if (!nm.isNotificationPolicyAccessGranted) return policyIntent()
        val on = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        return Outcome.Done
    }

    private fun toggleSystemSetting(id: String): Outcome {
        if (!Settings.System.canWrite(context)) {
            return Outcome.Open(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
                "Allow the launcher to change system settings"
            )
        }
        return try {
            val resolver = context.contentResolver
            if (id == "rotate") {
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (isOn("rotate")) 0 else 1)
            } else {
                Settings.System.putInt(
                    resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (isOn("brightness")) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
            }
            Outcome.Done
        } catch (_: Exception) {
            Outcome.Open(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
    }
}
