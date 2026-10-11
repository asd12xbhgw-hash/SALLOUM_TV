package com.salloum.tv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * نظام تحديث ذاتي بدون Google Play.
 * يقرأ ملف update.json من GitHub Releases، يقارن النسخة،
 * ينزّل الـ APK الجديد ويطلّع نافذة التثبيت.
 */
class AppUpdateManager(private val context: Context) {

    private val client = OkHttpClient()

    /** نتيجة فحص التحديث. */
    data class UpdateInfo(
        val available: Boolean,
        val latestVersion: String,
        val downloadUrl: String,
        val notes: String
    )

    /**
     * يفحص إذا في نسخة أحدث من المنشورة على GitHub.
     * @param updateJsonUrl رابط ملف update.json (مثلاً من GitHub Releases)
     */
    suspend fun checkForUpdate(updateJsonUrl: String): UpdateInfo =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(updateJsonUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UpdateInfo(false, "", "", "")
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val latestVersion = json.optString("versionName", "")
                    val versionCode = json.optInt("versionCode", 0)
                    val downloadUrl = json.optString("apkUrl", "")
                    val notes = json.optString("notes", "")

                    val currentVersionCode = context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .longVersionCode
                        .toInt()

                    val available = versionCode > currentVersionCode
                    UpdateInfo(available, latestVersion, downloadUrl, notes)
                }
            } catch (e: Exception) {
                UpdateInfo(false, "", "", "")
            }
        }

    /**
     * ينزّل الـ APK من الرابط إلى ملف مؤقت.
     * @return مسار الملف المنزّل، أو null إذا فشل.
     */
    suspend fun downloadApk(downloadUrl: String, onProgress: ((Int) -> Unit)? = null): File? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null

                    val body = response.body ?: return@withContext null
                    val total = body.contentLength()
                    val apkFile = File(context.cacheDir, "update.apk")

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var downloaded = 0L
                            var bytes: Int
                            while (input.read(buffer).also { bytes = it } != -1) {
                                output.write(buffer, 0, bytes)
                                downloaded += bytes
                                if (total > 0) {
                                    onProgress?.invoke(((downloaded * 100) / total).toInt())
                                }
                            }
                        }
                    }
                    apkFile
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * يطلّع نافذة تثبيت الـ APK المنزّل.
     * يتطلب صلاحية REQUEST_INSTALL_PACKAGES على أندرويد 8+.
     */
    fun installApk(activity: Activity, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    /** يفحص إذا التطبيق مخوّل يثبّت تطبيقات من مصادر خارجية (أندرويد 8+). */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** يفتح شاشة إعدادات السماح بالتثبيت من مصادر خارجية. */
    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivity(intent)
        }
    }
}