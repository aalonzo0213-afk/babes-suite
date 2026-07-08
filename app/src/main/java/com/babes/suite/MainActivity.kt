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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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
    }

    companion object {
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
