package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import moe.shizuku.manager.utils.ApkUtils.dispatchInstallerResult

/**
 * Receives install/uninstall results from the system package installer.
 *
 * This receiver is declared in the manifest with exported="false" and is only ever addressed
 * through an explicit PendingIntent, so other apps cannot send it anything.
 */
class InstallerResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmationIntent =
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            try {
                context.startActivity(confirmationIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.e(TAG, "Could not show the installer confirmation", e)
                dispatchInstallerResult(requestId, false, e.message)
            }
            return
        }

        dispatchInstallerResult(requestId, status == PackageInstaller.STATUS_SUCCESS, msg)
    }

    companion object {
        private const val TAG = "InstallerResultReceiver"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
