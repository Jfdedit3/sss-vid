package com.jfdedit3.sssvid

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jfdedit3.sssvid.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingDownload: DownloadData? = null
    private var pendingBlobDownload: BlobDownloadData? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDownload?.let { startHttpDownload(it) }
            pendingBlobDownload?.let { downloadBlobUrl(it.url, it.fileName, it.mimeType) }
        } else {
            Toast.makeText(this, "Permission de téléchargement refusée", Toast.LENGTH_LONG).show()
        }
        pendingDownload = null
        pendingBlobDownload = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(HOME_URL)
        }
    }

    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                return if (shouldBlockRequest(url)) {
                    emptyResponse()
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBlobDownloadHelper()
                binding.progressBar.hide()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress < 100) {
                    binding.progressBar.show()
                } else {
                    binding.progressBar.hide()
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val safeMimeType = mimeType.ifBlank { guessMimeTypeFromUrl(url) }
            val fileName = URLUtil.guessFileName(url, contentDisposition, safeMimeType)

            if (url.startsWith("blob:", ignoreCase = true)) {
                if (needsLegacyStoragePermission()) {
                    pendingBlobDownload = BlobDownloadData(url, fileName, safeMimeType)
                    requestLegacyStoragePermission()
                } else {
                    downloadBlobUrl(url, fileName, safeMimeType)
                }
                return@DownloadListener
            }

            val data = DownloadData(url, userAgent, contentDisposition, safeMimeType, fileName)

            if (needsLegacyStoragePermission()) {
                pendingDownload = data
                requestLegacyStoragePermission()
            } else {
                startHttpDownload(data)
            }
        })
    }

    private fun injectBlobDownloadHelper() {
        val js = """
            (function() {
                if (window.__androidBlobHelperInstalled) return;
                window.__androidBlobHelperInstalled = true;

                window.AndroidDownloadBlob = function(url, fileName, mimeType) {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', url, true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (xhr.status === 200 || xhr.status === 0) {
                                var blob = xhr.response;
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var dataUrl = reader.result || '';
                                    var base64 = dataUrl.split(',')[1] || '';
                                    var finalMime = mimeType || blob.type || 'application/octet-stream';
                                    var finalName = fileName || ('download_' + Date.now());
                                    AndroidBlobDownloader.saveBase64File(base64, finalName, finalMime);
                                };
                                reader.readAsDataURL(blob);
                            }
                        };
                        xhr.send();
                    } catch (e) {
                        console.log('Blob download bridge error', e);
                    }
                };
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    private fun downloadBlobUrl(url: String, fileName: String, mimeType: String) {
        val escapedUrl = jsEscape(url)
        val escapedName = jsEscape(fileName)
        val escapedMime = jsEscape(mimeType)
        val js = "window.AndroidDownloadBlob('$escapedUrl', '$escapedName', '$escapedMime');"
        binding.webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Préparation du téléchargement...", Toast.LENGTH_SHORT).show()
    }

    private fun jsEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun shouldBlockRequest(url: String): Boolean {
        if (url.isBlank()) return false

        val lowerUrl = url.lowercase(Locale.ROOT)
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")

        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return true
        }

        return AD_URL_KEYWORDS.any { lowerUrl.contains(it) }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun needsLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestLegacyStoragePermission() {
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun startHttpDownload(data: DownloadData) {
        try {
            val request = DownloadManager.Request(Uri.parse(data.url)).apply {
                setMimeType(data.mimeType)
                addRequestHeader("User-Agent", data.userAgent)

                val cookies = CookieManager.getInstance().getCookie(data.url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }

                setDescription("Téléchargement en cours...")
                setTitle(data.fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, data.fileName)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "Téléchargement lancé", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur téléchargement: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Impossible de créer le fichier")

                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Impossible d'écrire le fichier")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
            }

            Toast.makeText(this, "Téléchargement terminé: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur sauvegarde: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessMimeTypeFromUrl(url: String): String {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webm") -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        binding.webView.restoreState(savedInstanceState)
    }

    override fun onDestroy() {
        binding.webView.apply {
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                saveBase64File(base64Data, fileName, mimeType)
            }
        }
    }

    data class DownloadData(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
        val fileName: String
    )

    data class BlobDownloadData(
        val url: String,
        val fileName: String,
        val mimeType: String
    )

    companion object {
        private const val HOME_URL = "https://ssstwitter.com/"

        private val AD_HOSTS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adservice.google.fr",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "gstaticadssl.l.google.com",
            "facebook.net",
            "connect.facebook.net",
            "ads-twitter.com",
            "ads.yahoo.com",
            "amazon-adsystem.com",
            "branch.io",
            "appsflyer.com",
            "adjust.com",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com"
        )

        private val AD_URL_KEYWORDS = listOf(
            "/ads/",
            "/ad/",
            "doubleclick",
            "googlesyndication",
            "googleads",
            "adservice",
            "analytics",
            "tracking",
            "tracker",
            "pixel",
            "banner",
            "popunder",
            "popup"
        )
    }
}
