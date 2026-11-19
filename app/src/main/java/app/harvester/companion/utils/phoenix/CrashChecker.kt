package app.harvester.companion.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File

object CrashChecker {
    private const val PREFS_NAME = "uh_crash_state"
    private const val KEY_LAST_EXIT_TS = "last_handled_exit_ts"

    fun checkNativeCrashes(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkApi30(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkApi30(context: Context) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exitInfoList = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            if (exitInfoList.isEmpty()) return

            val exitInfo = exitInfoList[0]
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastHandledTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
            if (exitInfo.timestamp <= lastHandledTs) return

            val reason = exitInfo.reason
            if (reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ) {
                val crashFile = File(context.filesDir, "last_crash.txt")
                val traceStream = exitInfo.traceInputStream
                val description = exitInfo.description ?: "No description"

                val details = if (traceStream != null) {
                    traceStream.bufferedReader().use { it.readText() }
                } else {
                    "Exit Reason Code: $reason\n$description"
                }

                crashFile.writeText("ApplicationExitInfo Detected:\nReason: $reason\n$description\n\nDetails:\n$details")
                File(context.filesDir, "safe_mode.flag").createNewFile()
                prefs.edit().putLong(KEY_LAST_EXIT_TS, exitInfo.timestamp).apply()
            } else if (reason == ApplicationExitInfo.REASON_ANR) {
                val traceStream = exitInfo.traceInputStream
                if (traceStream != null) {
                    val crashDir = File(context.filesDir, "crashes").apply { mkdirs() }
                    val anrFile = File(crashDir, "anr_${exitInfo.timestamp}.txt")
                    val details = traceStream.bufferedReader().use { it.readText() }
                    val description = exitInfo.description ?: "ANR"
                    anrFile.writeText("ApplicationExitInfo ANR:\n$description\n\n$details")
                }
                prefs.edit().putLong(KEY_LAST_EXIT_TS, exitInfo.timestamp).apply()
            }
        } catch (_: Exception) {}
    }
}
