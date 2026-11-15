package moe.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import android.os.PowerManager
import android.provider.Settings
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.SettingsPage

object SettingsHelper {

    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
    private const val DEFAULT_ADB_ALLOWED_CONNECTION_TIME = 7L * 24 * 60 * 60 * 1000

    /**
     * Stops Android from revoking ADB authorizations that were unused for 7 days, if the user
     * left that option on. Requires WRITE_SECURE_SETTINGS.
     */
    fun keepAdbAuthorizationIfEnabled(context: Context) {
        if (!ShizukuSettings.getKeepAdbAuthorization()) return
        Settings.Global.putLong(context.contentResolver, ADB_ALLOWED_CONNECTION_TIME, 0L)
    }

    /** Puts the system's default 7 day expiry of unused ADB authorizations back in place. */
    fun restoreAdbAuthorizationExpiry(context: Context): Boolean {
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            Settings.Global.putLong(
                context.contentResolver,
                ADB_ALLOWED_CONNECTION_TIME,
                DEFAULT_ADB_ALLOWED_CONNECTION_TIME
            )
        } catch (e: SecurityException) {
            false
        }
    }

    fun launchOrHighlightWirelessDebugging(context: Context) {
        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled > 0) {
            SettingsPage.Developer.WirelessDebugging.launch(context)
        } else SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context, launcher: ActivityResultLauncher<Intent>? = null) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:" + context.packageName))
        }
        if (launcher != null) {
            launcher.launch(intent)
        } else {
            context.startActivity(intent)
        }
    }

}