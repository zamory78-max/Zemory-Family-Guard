package com.familyguard.child.apps

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Silent install / uninstall (silent when Device Owner, otherwise the system asks the user). */
object Installer {
    const val ACTION_RESULT = "com.familyguard.child.INSTALL_RESULT"
    const val EXTRA_CMD = "cmdId"

    suspend fun installFromUrl(ctx: Context, url: String, cmdId: String) = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "download.apk")
        download(url, file)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(resultSender(ctx, cmdId, sessionId))
        }
        file.delete()
    }

    fun uninstall(ctx: Context, pkg: String, cmdId: String) {
        ctx.packageManager.packageInstaller.uninstall(pkg, resultSender(ctx, cmdId, cmdId.hashCode()))
    }

    private fun resultSender(ctx: Context, cmdId: String, requestCode: Int): IntentSender {
        val i = Intent(ctx, InstallReceiver::class.java)
            .setAction(ACTION_RESULT)
            .putExtra(EXTRA_CMD, cmdId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, requestCode, i, flags).intentSender
    }

    private fun download(url: String, dest: File) {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "FamilyGuard")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            return
        }
        throw IllegalStateException("too many redirects")
    }
}
