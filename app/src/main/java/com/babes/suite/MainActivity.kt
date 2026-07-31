package com.babes.suite

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    // Handles <input type="file"> for JSON restore / import buttons
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else arrayOf())
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage — persists safely in app data
            allowFileAccess = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.addJavascriptInterface(ExportBridge(), "AndroidBridge")

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Intercept clicks on <a download="..."> so blob exports
                // (JSON backups, xlsx files) save to the tablet's Downloads folder.
                webView.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                fileChooser.launch("*/*")
                return true
            }
        }

        // Back button walks back through pages (app -> home screen) before exiting
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack()
            else finish()
        }

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CATERING_CHANNEL_ID, "Catering Reminders", NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Reminders for upcoming catering events"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun parseEventTime(raw: String): Pair<Int, Int>? {
        val cleaned = raw.trim().uppercase(Locale.US)
        if (cleaned.isEmpty()) return null
        val patterns = listOf("h:mm a", "hh:mm a", "h a", "ha", "H:mm")
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.isLenient = false
                val d = sdf.parse(cleaned) ?: continue
                val c = Calendar.getInstance()
                c.time = d
                return Pair(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    private fun scheduleOne(uniqueName: String, title: String, message: String, trigger: Calendar, notifId: Int) {
        val wm = WorkManager.getInstance(applicationContext)
        val delay = trigger.timeInMillis - System.currentTimeMillis()
        if (delay <= 0) {
            wm.cancelUniqueWork(uniqueName)
            return
        }
        val data = Data.Builder()
            .putString("title", title)
            .putString("message", message)
            .putInt("notifId", notifId)
            .build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    inner class ExportBridge {
        @JavascriptInterface
        fun saveFile(fileName: String, mimeType: String, base64Data: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val savedTo: String

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IllegalStateException("Could not create file")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    savedTo = "Downloads/$fileName"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: filesDir
                    val f = File(dir, fileName)
                    f.writeBytes(bytes)
                    savedTo = f.absolutePath
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved: $savedTo", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun scheduleReminders(eventId: String, title: String, dateIso: String, timeText: String) {
            try {
                val parts = dateIso.split("-").map { it.toInt() }
                val base = Calendar.getInstance()
                base.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
                base.set(Calendar.MILLISECOND, 0)

                val parsedTime = parseEventTime(timeText)
                val wm = WorkManager.getInstance(applicationContext)

                if (parsedTime != null) {
                    val startCal = base.clone() as Calendar
                    startCal.set(Calendar.HOUR_OF_DAY, parsedTime.first)
                    startCal.set(Calendar.MINUTE, parsedTime.second)

                    val twoHrCal = startCal.clone() as Calendar
                    twoHrCal.add(Calendar.HOUR_OF_DAY, -2)

                    val morningCal = base.clone() as Calendar
                    morningCal.set(Calendar.HOUR_OF_DAY, 11)
                    morningCal.set(Calendar.MINUTE, 0)

                    val startsAfter11 = parsedTime.first > 11 || (parsedTime.first == 11 && parsedTime.second > 0)

                    if (startsAfter11) {
                        scheduleOne("$eventId-morning", title, "Coming up today", morningCal, ("$eventId-morning").hashCode())
                        scheduleOne("$eventId-2hr", title, "Starts in 2 hours", twoHrCal, ("$eventId-2hr").hashCode())
                    } else {
                        scheduleOne("$eventId-morning", title, "Starts in 2 hours", twoHrCal, ("$eventId-morning").hashCode())
                        wm.cancelUniqueWork("$eventId-2hr")
                    }
                } else {
                    val morningCal = base.clone() as Calendar
                    morningCal.set(Calendar.HOUR_OF_DAY, 11)
                    morningCal.set(Calendar.MINUTE, 0)
                    scheduleOne("$eventId-morning", title, "Coming up today", morningCal, ("$eventId-morning").hashCode())
                    wm.cancelUniqueWork("$eventId-2hr")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Couldn't read the event time - only the 11 AM reminder was scheduled.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Couldn't schedule reminder: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun cancelReminders(eventId: String) {
            val wm = WorkManager.getInstance(applicationContext)
            wm.cancelUniqueWork("$eventId-morning")
            wm.cancelUniqueWork("$eventId-2hr")
        }
    }

    companion object {
        const val CATERING_CHANNEL_ID = "catering_reminders"

        // Captures clicks on download links, converts the blob to base64,
        // and hands it to the native side to write into Downloads.
        private const val DOWNLOAD_HOOK_JS = """
            (function() {
                if (window.__babesHooked) return;
                window.__babesHooked = true;
                document.addEventListener('click', function(e) {
                    var a = e.target.closest ? e.target.closest('a[download]') : null;
                    if (!a || !a.href) return;
                    e.preventDefault();
                    var name = a.getAttribute('download') || 'export.dat';
                    fetch(a.href)
                        .then(function(r) { return r.blob(); })
                        .then(function(blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var b64 = reader.result.split(',')[1];
                                AndroidBridge.saveFile(name, blob.type || '', b64);
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(function(err) { alert('Export failed: ' + err); });
                }, true);
            })();
        """
    }
}
