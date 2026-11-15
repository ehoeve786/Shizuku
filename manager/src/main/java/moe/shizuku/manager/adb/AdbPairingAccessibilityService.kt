package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.provider.Settings
import android.content.ActivityNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import android.os.Build
import java.net.ConnectException
import java.net.SocketException

class AdbPairingAccessibilityService : AccessibilityService() {

    var host: String? = null
    var port: Int? = null
    var password: String? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        if (!(EnvironmentUtils.isTelevision() && EnvironmentUtils.isTlsSupported())) {
            Toast.makeText(this, getString(R.string.toast_accessibility_tv_only), Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, true)
        }
        startActivity(intent)

        handler.postDelayed({
            Toast.makeText(this, getString(R.string.toast_pairing_timeout), Toast.LENGTH_LONG).show()
            disableSelf()
        }, 60_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (port != null && password != null) return

        if ((event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) == 0) return
        val text = event.source?.text ?: return

        val ipPortRegex = Regex("""((?:\d{1,3}\.){3}\d{1,3}):(\d{2,5})""")
        val passwordRegex = Regex("""\d{6}""")

        ipPortRegex.matchEntire(text)?.let { match ->
            match.groupValues[2].toIntOrNull()?.let {
                host = match.groupValues[1]
                port = it
            }
        }
        passwordRegex.matchEntire(text)
            ?.value
            ?.let { password = it }

        if (port != null && password != null) {
            val port = port!!
            val password = password!!

            var toastMsg = getString(R.string.notification_adb_pairing_failed_title)
            // Android 17 restricts loopback connections, so prefer the address shown on screen
            // there. Older versions are known to work over loopback.
            val loopback = "127.0.0.1"
            val hosts =
                (if (Build.VERSION.SDK_INT >= 37) listOf(host, loopback) else listOf(loopback, host))
                    .filterNotNull()
                    .distinct()

            GlobalScope.launch(Dispatchers.IO) {
                val key = try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                } catch (e: Throwable) {
                    toastMsg = getString(R.string.adb_error_key_store)
                    null
                }

                if (key != null) {
                    var result: Result<Boolean> = Result.failure(ConnectException())
                    for (candidate in hosts) {
                        result = AdbPairingClient(candidate, port, password, key).runCatching {
                            start()
                        }
                        // Only try the next address if this one could not be reached
                        if (result.exceptionOrNull() !is SocketException) break
                    }

                    result.onFailure {
                        when (it) {
                            is SocketException -> toastMsg = getString(R.string.cannot_connect_port)
                            is AdbInvalidPairingCodeException -> toastMsg = getString(R.string.paring_code_is_wrong)
                            is AdbKeyException -> toastMsg = getString(R.string.adb_error_key_store)
                        }
                    }.onSuccess {
                        if (it) {
                            toastMsg = "${getString(R.string.notification_adb_pairing_succeed_title)}. ${getString(R.string.notification_adb_pairing_succeed_text)}"

                            val intent = Intent(this@AdbPairingAccessibilityService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(intent)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbPairingAccessibilityService, toastMsg, Toast.LENGTH_LONG).show()
                }
                disableSelf()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

}