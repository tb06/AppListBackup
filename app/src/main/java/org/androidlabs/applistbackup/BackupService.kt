package org.androidlabs.applistbackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import org.androidlabs.applistbackup.reader.BackupReaderActivity
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class BackupFile(
    val uri: Uri,
    val date: Date,
    val title: String
)

class BackupService : Service() {
    private val tag: String = "BackupService"
    
    companion object {
        const val SERVICE_CHANNEL_ID = "BackupService"
        const val BACKUP_CHANNEL_ID = "Backup"

        private var onCompleteCallback: ((Uri) -> Unit)? = null

        private const val PREFERENCES_FILE: String = "preferences"
        private const val KEY_BACKUP_URI: String = "backup_uri"

        fun setBackupUri(context: Context, uri: Uri) {
            val sharedPreferences = context.getSharedPreferences(PREFERENCES_FILE, MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString(KEY_BACKUP_URI, uri.toString())
            editor.apply()
        }

        fun getBackupUri(context: Context): Uri? {
            val sharedPreferences = context.getSharedPreferences(PREFERENCES_FILE, MODE_PRIVATE)
            val uriString = sharedPreferences.getString(KEY_BACKUP_URI, null)
            return if (uriString != null) Uri.parse(uriString) else null
        }

        fun getBackupFolder(context: Context): DocumentFile? {
            val backupsUri = getBackupUri(context) ?: return null
            return DocumentFile.fromTreeUri(context, backupsUri)
        }

        fun getReadablePathFromUri(uri: Uri?): String {
            if (uri == null) {
                return ""
            }
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val path = split.getOrNull(1) ?: ""
            val decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8.toString())

            return when (type) {
                "primary" -> "Internal Storage/${clearPrefixSlash(decodedPath)}"
                "home" -> "Home/${clearPrefixSlash(decodedPath)}"
                "raw" -> {
                    val internalPath = Environment.getExternalStorageDirectory().path
                    when {
                        decodedPath.startsWith(internalPath) -> {
                            "Internal Storage/${clearPrefixSlash(decodedPath.removePrefix(internalPath))}"
                        }
                        else -> {
                            decodedPath
                        }
                    }
                }
                else -> "$type/${clearPrefixSlash(decodedPath)}"
            }
        }

        fun getLastCreatedFileUri(context: Context): Uri? {
            val backupsUri = getBackupUri(context) ?: return null
            val backupsDir = DocumentFile.fromTreeUri(context, backupsUri)

            if (backupsDir != null && backupsDir.exists() && backupsDir.isDirectory) {
                val files = backupsDir.listFiles()

                if (files.isNotEmpty()) {
                    val sortedFiles = files.sortedByDescending { it.lastModified() }

                    val lastCreatedFile = sortedFiles.firstOrNull()

                    if (lastCreatedFile != null) {
                        return lastCreatedFile.uri
                    }
                }
            }
            return null
        }

        fun getBackupFiles(context: Context): List<BackupFile> {
            val backupsUri = getBackupUri(context) ?: return emptyList()
            val backupsDir = DocumentFile.fromTreeUri(context, backupsUri) ?: return emptyList()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val titleFormatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

            return backupsDir.listFiles()
                .filter { file ->
                    file.name?.let { name ->
                        name.endsWith(".html") && name.startsWith("app-list-backup-")
                    } == true
                }
                .map { file ->
                    val name = file.name ?: return@map null
                    val dateString = name.removePrefix("app-list-backup-").removeSuffix(".html")
                    val date = dateFormat.parse(dateString) ?: Date()
                    val title = titleFormatter.format(date)
                    BackupFile(file.uri, date, title)
                }
                .filterNotNull()
                .sortedByDescending { it.date }
        }

        fun parseDateFromUri(uri: Uri): Date? {
            val pattern = Pattern.compile("app-list-backup-(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})\\.html")
            val matcher = pattern.matcher(uri.toString())

            return if (matcher.find()) {
                val dateString = matcher.group(1)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                dateString?.let {
                    dateFormat.parse(it)
                }
            } else {
                null
            }
        }

        fun run(context: Context, onComplete: ((Uri) -> Unit)? = null) {
            if (onComplete != null) {
                onCompleteCallback = onComplete
            }

            val intent = Intent(context, BackupService::class.java)
            context.startForegroundService(intent)

            val broadcastIntent = Intent("org.androidlabs.applistbackup.BACKUP_ACTION")
            context.sendBroadcast(broadcastIntent)
        }

        private fun clearPrefixSlash(path: String): String {
            val prefix = "/"
            if (path.startsWith(prefix)) {
                return path.removePrefix(prefix)
            }
            return path
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "start: ${intent.toString()}")
        val startDate = Date()
        val source = intent?.getStringExtra("source")
        createNotificationChannels()

        val backupsDir = getBackupFolder(this)

        if (backupsDir != null) {
            val appWidgetManager = AppWidgetManager.getInstance(this)
            val thisAppWidget = ComponentName(this.packageName, BackupWidget::class.java.name)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(this, appWidgetManager, appWidgetId, showLoading = true)
            }

            val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle(getString(R.string.backup_started))
                .setContentText(getString(R.string.in_progress))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()

            startForeground(1, notification)

            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                val outputDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                val currentDate = Date()
                val currentTime = dateFormat.format(currentDate)

                val template = assets.open("template.html").bufferedReader().use { it.readText() }
                val appItems = StringBuilder()

                var systemAppsCount = 0
                var appsCount = 0
                var enabledAppsCount = 0
                val apps = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                val activeApps = packageManager.queryIntentActivities(mainIntent, 0).map { it.activityInfo.packageName }

                apps.forEachIndexed { index, packageInfo ->
                    val appInfo = packageInfo.applicationInfo ?: return@forEachIndexed
                    val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                    if (isSystem && !activeApps.contains(packageInfo.packageName)) {
                        return@forEachIndexed
                    }
                    val name = packageManager.getApplicationLabel(appInfo)
                    val packageName = appInfo.packageName
                    val icon = packageManager.getApplicationIcon(appInfo)
                    if (isSystem) {
                        systemAppsCount++
                    }
                    appsCount++

                    if (appInfo.enabled) {
                        enabledAppsCount++
                    }

                    appItems.append(
                        """
                <div class="app-item"
                data-install-time="${packageInfo.firstInstallTime}"
                data-update-time="${packageInfo.lastUpdateTime}"
                data-app-name="$name"
                data-package-name="$packageName"
                data-is-system-app="$isSystem"
                data-is-enabled="${appInfo.enabled}"
                data-default-order="$index">
                    <img src="${drawableToBase64(icon)}" alt="$name">
                    <div class="app-details">
                        <strong class="app-name">$name</strong><br>
                        <strong>${getString(R.string.package_title)}:</strong> $packageName<br>
                        <strong>${getString(R.string.system_title)}:</strong> ${isSystem}<br>
                        <strong>${getString(R.string.enabled_title)}:</strong> ${appInfo.enabled}<br>
                        <strong>${getString(R.string.version_title)}:</strong> ${packageInfo.versionName} (${packageInfo.longVersionCode})<br>
                        <strong>${getString(R.string.min_sdk_version_title)}:</strong> ${appInfo.minSdkVersion}<br>
                        <strong>${getString(R.string.installed_at_title)}:</strong> ${outputDateFormat.format(Date(packageInfo.firstInstallTime))}<br>
                        <strong>${getString(R.string.updated_at_title)}:</strong> ${outputDateFormat.format(Date(packageInfo.lastUpdateTime))}<br>
                        <strong>${getString(R.string.links_title)}</strong> (${getString(R.string.links_title_details)}):<br>
                        <a target="_blank" rel="noopener noreferrer" href="https://play.google.com/store/apps/details?id=$packageName">Play Market</a> | 
                        <a target="_blank" rel="noopener noreferrer" href="https://f-droid.org/packages/$packageName">F-Droid</a>
                    </div>
                </div>
            """.trimIndent()
                    )
                }

                val fileName = "app-list-backup-$currentTime.html"
                val type = getString(if (source != null && source == "tasker") R.string.automatic else R.string.manual)
                val userAppsCount = appsCount - systemAppsCount
                val disabledAppsCount = appsCount - enabledAppsCount

                val durationMillis = Date().time - startDate.time
                val durationSeconds = durationMillis / 1000.0
                val decimalFormat = DecimalFormat("0.000 ${getString(R.string.seconds)}")
                val formattedDuration = decimalFormat.format(durationSeconds)

                val placeholders = mapOf(
                    "APP_ITEMS_PLACEHOLDER" to appItems.toString(),
                    "BACKUP_TIME_PLACEHOLDER" to outputDateFormat.format(currentDate),
                    "TRIGGER_TYPE_PLACEHOLDER" to type,
                    "TOTAL_APPS_COUNT_PLACEHOLDER" to appsCount.toString(),
                    "USER_APPS_COUNT_PLACEHOLDER" to userAppsCount.toString(),
                    "SYSTEM_APPS_COUNT_PLACEHOLDER" to systemAppsCount.toString(),
                    "ENABLED_APPS_COUNT_PLACEHOLDER" to enabledAppsCount.toString(),
                    "DISABLED_APPS_COUNT_PLACEHOLDER" to disabledAppsCount.toString(),
                    "BACKUP_DURATION_PLACEHOLDER" to formattedDuration,

                    "LOCALISATION_CREATED_AT" to getString(R.string.created_at),
                    "LOCALISATION_TRIGGER_TYPE" to getString(R.string.trigger_type),
                    "LOCALISATION_TOTAL_APPS_COUNT" to getString(R.string.total_apps_count),
                    "LOCALISATION_USER_APPS_COUNT" to getString(R.string.user_apps_count),
                    "LOCALISATION_SYSTEM_APPS_COUNT" to getString(R.string.system_apps_count),
                    "LOCALISATION_ENABLED_APPS_COUNT" to getString(R.string.enabled_apps_count),
                    "LOCALISATION_DISABLED_APPS_COUNT" to getString(R.string.disabled_apps_count),
                    "LOCALISATION_INSTALLED_APPS_COUNT" to getString(R.string.installed_apps_count),
                    "LOCALISATION_UNINSTALLED_APPS_COUNT" to getString(R.string.uninstalled_apps_count),
                    "LOCALISATION_SEARCH_PLACEHOLDER" to getString(R.string.search_placeholder),
                    "LOCALISATION_SORT_OPTIONS" to getString(R.string.sort_options),
                    "LOCALISATION_FILTER_OPTIONS" to getString(R.string.filter_options),
                    "LOCALISATION_NO_ITEMS_PLACEHOLDER" to getString(R.string.no_items_placeholder),
                    "LOCALISATION_SORTING" to getString(R.string.sorting),
                    "LOCALISATION_SORT_BY_DEFAULT" to getString(R.string.sort_by_default),
                    "LOCALISATION_SORT_BY_INSTALL_TIME" to getString(R.string.sort_by_install_time),
                    "LOCALISATION_SORT_BY_UPDATE_TIME" to getString(R.string.sort_by_update_time),
                    "LOCALISATION_SORT_BY_APP_NAME" to getString(R.string.sort_by_app_name),
                    "LOCALISATION_SORT_BY_PACKAGE_NAME" to getString(R.string.sort_by_package_name),
                    "LOCALISATION_ORDER" to getString(R.string.order),
                    "LOCALISATION_ORDER_ASCENDING" to getString(R.string.order_ascending),
                    "LOCALISATION_ORDER_DESCENDING" to getString(R.string.order_descending),
                    "LOCALISATION_CLOSE_BUTTON" to getString(R.string.close),
                    "LOCALISATION_APPS_FILTERING" to getString(R.string.apps_filtering),
                    "LOCALISATION_INCLUDE_USER_APPS" to getString(R.string.include_user_apps),
                    "LOCALISATION_INCLUDE_SYSTEM_APPS" to getString(R.string.include_system_apps),
                    "LOCALISATION_INCLUDE_ENABLED_APPS" to getString(R.string.include_enabled_apps),
                    "LOCALISATION_INCLUDE_DISABLED_APPS" to getString(R.string.include_disabled_apps),
                    "LOCALISATION_INCLUDE_INSTALLED_APPS" to getString(R.string.include_installed_apps),
                    "LOCALISATION_APPLY_FILTERS_BUTTON" to getString(R.string.apply_filters_button),
                    "LOCALISATION_BACKUP_DURATION" to getString(R.string.backup_duration),
                )

                var finalHtml = template

                placeholders.forEach { (placeholder, value) ->
                    finalHtml = finalHtml.replace("<!-- $placeholder -->", value)
                }

                val newFile = backupsDir.createFile("text/html", fileName)

                if (newFile != null) {
                    contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                        outputStream.write(finalHtml.toByteArray())
                    }
                } else {
                    throw Error(getString(R.string.file_create_failed))
                }

                val openFileIntent = Intent(this, BackupReaderActivity::class.java).apply {
                    putExtra("uri", newFile.uri.toString())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

                val pendingIntent = PendingIntent.getActivity(this, 0, openFileIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val successfulTitle = getString(R.string.backup_done_title, appsCount.toString(), type)
                val successfulText = getString(R.string.backup_done_text, userAppsCount.toString(), systemAppsCount.toString())

                val endNotification = NotificationCompat.Builder(this, BACKUP_CHANNEL_ID)
                    .setContentTitle(successfulTitle)
                    .setContentText(successfulText)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(pendingIntent)
                    .build()

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(getNotificationId(), endNotification)

                if (onCompleteCallback != null) {
                    onCompleteCallback?.let { it(newFile.uri) }
                    onCompleteCallback = null
                }
            } catch (exception: Exception) {
                val endNotification = NotificationCompat.Builder(this, BACKUP_CHANNEL_ID)
                    .setContentTitle(getString(R.string.backup_failed))
                    .setContentText(exception.localizedMessage)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build()

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(getNotificationId(), endNotification)
            }

            Log.d(tag, "end")

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(this, appWidgetManager, appWidgetId, showLoading = false)
            }
        } else {
            Log.d(tag, "failed due no destination")

            val endNotification = NotificationCompat.Builder(this, BACKUP_CHANNEL_ID)
                .setContentTitle(getString(R.string.backup_failed))
                .setContentText(getString(R.string.destination_not_set_notification))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(getNotificationId(), endNotification)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)

        return START_STICKY
    }

    private fun getNotificationId(): Int {
        return (System.currentTimeMillis() and 0xfffffff).toInt()
    }

    private fun drawableToBase64(drawable: Drawable): String {
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()

        return "data:image/png;base64, " + Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun createNotificationChannels() {
        val foregroundServiceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Backup Service Notification",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        // Channel for End Notifications
        val endNotificationChannel = NotificationChannel(
            BACKUP_CHANNEL_ID,
            "Backup Notification",
            NotificationManager.IMPORTANCE_HIGH
        )

        // Register both channels with the system
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(foregroundServiceChannel)
        manager.createNotificationChannel(endNotificationChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "destroy")
    }
}