package com.smaznet.teleplay.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** What to do before handing a package to the system installer. */
enum class InstallDecision { Install, NeedPermission }

fun installDecision(sdk: Int, canRequestInstalls: Boolean): InstallDecision =
    if (sdk < Build.VERSION_CODES.O || canRequestInstalls) InstallDecision.Install else InstallDecision.NeedPermission

/** Hands a verified APK to the system installer; the viewer approves in the system's own prompt. */
class ApkInstaller(private val context: Context) {
    fun canInstall(): Boolean =
        installDecision(Build.VERSION.SDK_INT, canRequestInstallsOrFalse()) == InstallDecision.Install

    private fun canRequestInstallsOrFalse(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()

    /** Starts the system installer for [file]. False when no activity can handle it. */
    fun install(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /** Opens the system screen where the viewer can allow TelePlay to install apps (API 26+). */
    fun openPermissionSettings(): Boolean = try {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
