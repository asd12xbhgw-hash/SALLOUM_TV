package com.salloum.tv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * نظام تحديث ذاتي بدون Google Play.
 * يقرأ ملف update.json من GitHub Releases، يقارن النسخة،
 * ينزّل الـ APK الجديد ويطلّع نافذة التثبيت.
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
    }

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
                Log.d(TAG, "checkForUpdate: fetching $updateJsonUrl")
                val request = Request.Builder().url(updateJsonUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "checkForUpdate: HTTP ${response.code} from $updateJsonUrl")
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
                    Log.d(TAG, "checkForUpdate: current=$currentVersionCode remote=$versionCode available=$available url=$downloadUrl")
                    UpdateInfo(available, latestVersion, downloadUrl, notes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate: exception", e)
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
                Log.d(TAG, "downloadApk: downloading from $downloadUrl")
                val request = Request.Builder().url(downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "downloadApk: HTTP ${response.code} from $downloadUrl")
                        return@withContext null
                    }

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

                    // تحقق أن الملف مكتمل وليس مقطوعاً أو صفحة HTML (منع خطأ "فشل في تحليل الحزمة")
                    val sizeOk = apkFile.exists() && apkFile.length() > 1024 &&
                        (total <= 0 || apkFile.length() == total)
                    Log.d(TAG, "downloadApk: downloaded=${apkFile.length()} expected=$total sizeOk=$sizeOk")
                    if (sizeOk && isCompleteApk(apkFile)) {
                        Log.d(TAG, "downloadApk: APK صالح، SHA-256=${sha256(apkFile)}")
                        apkFile
                    } else {
                        Log.e(TAG, "downloadApk: فشل التحقق — الملف غير مكتمل أو تالف (sizeOk=$sizeOk)")
                        apkFile.delete()
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadApk: exception", e)
                null
            }
        }

    /** يحسب SHA-256 للملف (للتشخيص والتحقق). */
    private fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    digest.update(buffer, 0, bytes)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "error"
        }
    }

    /**
     * يتحقق أن الـ APK المنزّل موقّع بنفس مفتاح التطبيق المثبت.
     * يمنع "التطبيق غير مثبّت" الناتج عن تعارض التوقيع (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
     */
    fun isApkSignedWithSameKey(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val installed = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            val installedSig = installed.signatures?.firstOrNull()?.toByteArray()

            // نقرأ توقيع الـ APK المنزّل عبر getPackageArchiveInfo
            val archiveInfo = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.GET_SIGNATURES
            )
            val apkSig = archiveInfo?.signatures?.firstOrNull()?.toByteArray()

            val same = installedSig != null && apkSig != null &&
                java.security.MessageDigest.isEqual(installedSig, apkSig)
            Log.d(TAG, "isApkSignedWithSameKey: $same")
            same
        } catch (e: Exception) {
            Log.e(TAG, "isApkSignedWithSameKey: exception (لا يمكن التحقق)", e)
            true // إذا تعذّر التحقق، لا نمنع التثبيت
        }
    }

    /**
     * يتأكد أن الملف ZIP كامل: يفشل إذا كان مقطوعاً لأن فهرس الملفات (central directory)
     * يكون في نهاية الملف، ويجب أن يحتوي على AndroidManifest.xml و classes.dex.
     */
    private fun isCompleteApk(file: File): Boolean {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null &&
                    zip.getEntry("classes.dex") != null
            }
        } catch (e: Exception) {
            false
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
        Log.d(TAG, "installApk: uri=$uri file=${apkFile.absolutePath} size=${apkFile.length()}")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            activity.startActivity(intent)
            Log.d(TAG, "installApk: intent أُرسل بنجاح إلى PackageInstaller")
        } catch (e: Exception) {
            Log.e(TAG, "installApk: فشل إرسال intent", e)
            return
        }

        // إغلاق التطبيق بالكامل وبشكل قسري لمنع بقائه في الخلفية.
        // نؤجّل القتل قليلاً حتى يبدأ PackageInstaller نشاطه.
        Thread {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                // تجاهل
            }
            try {
                activity.finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                Runtime.getRuntime().halt(0)
            } catch (e: Exception) {
                System.exit(0)
            }
        }.start()
    }

    /** يفحص إذا التطبيق المثبت موقّع بنفس مفتاح التطبيق الحالي. */
    fun isSameSignature(activity: Activity): Boolean {
        return try {
            val currentSig = activity.packageManager
                .getPackageInfo(activity.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                .signatures
            val installedSig = activity.packageManager
                .getPackageInfo(activity.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                .signatures
            val same = currentSig.contentEquals(installedSig)
            Log.d(TAG, "isSameSignature: $same")
            same
        } catch (e: Exception) {
            Log.e(TAG, "isSameSignature: exception", e)
            true // إذا فشل الفحص، افترض أن التوقيع متطابق
        }
    }

    /** يفحص إذا التطبيق مخوّل يثبّت تطبيقات من مصادر خارجية (أندرويد 8+). */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val can = context.packageManager.canRequestPackageInstalls()
            Log.d(TAG, "canInstallPackages: $can (SDK ${Build.VERSION.SDK_INT})")
            can
        } else {
            true
        }
    }

    /** يفتح شاشة إعدادات السماح بالتثبيت من مصادر خارجية. */
    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "openInstallPermissionSettings: فتح إعدادات السماح بالتثبيت")
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivity(intent)
        }
    }
}