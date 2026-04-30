package com.jfdedit3.sssvid

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import android.webkit.MimeTypeMap
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val storageGranted = !needsLegacyStoragePermission() ||
            result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true

        if (storageGranted) {
            pendingDownload?.let { startHttpDownload(it) }
            pendingBlobDownload?.let { downloadBlobUrl(it.url, it.fileName, it.mimeType) }
        } else if (pendingDownload != null || pendingBlobDownload != null) {
            Toast.makeText(this, "Permission de téléchargement refusée", Toast.LENGTH_LONG).show()
        }

        pendingDownload = null
        pendingBlobDownload = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestStartupPermissions()
        ensureLegacyDownloadDirectoryExists()
        setupWebView()
        setupBottomBar()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(HOME_URL)
        }
    }

    private fun setupBottomBar() {
        binding.siteButton.setOnClickListener {
            Toast.makeText(this, "Site web SSS: $HOME_URL", Toast.LENGTH_LONG).show()
            binding.webView.loadUrl(HOME_URL)
        }

        binding.galleryButton.setOnClickListener {
            if (needsGalleryReadPermission()) {
                requestGalleryPermissions()
                Toast.makeText(this, "Autorisation lecture requise pour la galerie", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, GalleryActivity::class.java))
            }
        }
    }

    private fun requestStartupPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (needsLegacyStoragePermission()) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (needsGalleryReadPermission()) {
            permissionsToRequest.addAll(galleryReadPermissions())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.distinct().toTypedArray())
        }
    }

    private fun requestGalleryPermissions() {
        val permissionsToRequest = galleryReadPermissions().toMutableList()
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.distinct().toTypedArray())
        }
    }

    private fun galleryReadPermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyList()
        }
    }

    private fun needsGalleryReadPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            else -> false
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
            val detectedMimeType = normalizeMimeType(mimeType.ifBlank { guessMimeTypeFromUrl(url) })
            val rawFileName = URLUtil.guessFileName(url, contentDisposition, detectedMimeType)
            val fileName = ensureFileNameHasCorrectExtension(rawFileName, detectedMimeType)

            if (url.startsWith("blob:", ignoreCase = true)) {
                if (needsLegacyStoragePermission()) {
                    pendingBlobDownload = BlobDownloadData(url, fileName, detectedMimeType)
                    requestDownloadPermissionsIfNeeded()
                } else {
                    downloadBlobUrl(url, fileName, detectedMimeType)
                }
                return@DownloadListener
            }

            val data = DownloadData(url, userAgent, contentDisposition, detectedMimeType, fileName)

            if (needsLegacyStoragePermission()) {
                pendingDownload = data
                requestDownloadPermissionsIfNeeded()
            } else {
                startHttpDownload(data)
            }
        })
    }

    private fun requestDownloadPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (needsLegacyStoragePermission()) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun ensureLegacyDownloadDirectoryExists() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val appFolder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APP_FOLDER_NAME
            )
            if (!appFolder.exists()) {
                appFolder.mkdirs()
            }
        }
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

    private fun startHttpDownload(data: DownloadData) {
        try {
            val finalFileName = ensureFileNameHasCorrectExtension(data.fileName, data.mimeType)
            val request = DownloadManager.Request(Uri.parse(data.url)).apply {
                setMimeType(data.mimeType)
                addRequestHeader("User-Agent", data.userAgent)

                val cookies = CookieManager.getInstance().getCookie(data.url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }

                setDescription("Téléchargement en cours...")
                setTitle(finalFileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$APP_FOLDER_NAME/$finalFileName"
                )
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "Téléchargement lancé: $finalFileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur téléchargement: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        try {
            val normalizedMimeType = normalizeMimeType(mimeType)
            val finalFileName = ensureFileNameHasCorrectExtension(fileName, normalizedMimeType)
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Downloads.MIME_TYPE, normalizedMimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, downloadsRelativePath())
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
                val appFolder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    APP_FOLDER_NAME
                )
                if (!appFolder.exists()) appFolder.mkdirs()
                val file = File(appFolder, finalFileName)
                FileOutputStream(file).use { it.write(bytes) }
            }

            Toast.makeText(this, "Téléchargement terminé: $finalFileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur sauvegarde: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun normalizeMimeType(mimeType: String?): String {
        val value = mimeType.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            value.isBlank() -> "video/mp4"
            value == "application/octet-stream" -> "video/mp4"
            value == "binary/octet-stream" -> "video/mp4"
            value.contains("mp4") -> "video/mp4"
            value.contains("webm") -> "video/webm"
            value.contains("mpeg") || value.contains("mp3") -> "audio/mpeg"
            value.contains("jpeg") || value.contains("jpg") -> "image/jpeg"
            value.contains("png") -> "image/png"
            else -> value
        }
    }

    private fun ensureFileNameHasCorrectExtension(fileName: String, mimeType: String): String {
        val cleaned = fileName.trim().ifBlank { "download_${System.currentTimeMillis()}" }
        val extension = extensionFromMimeType(mimeType)

        if (extension.isBlank()) return cleaned

        val lowerName = cleaned.lowercase(Locale.ROOT)
        if (lowerName.endsWith(".$extension")) return cleaned

        if (lowerName.endsWith(".bin") || '.' !in cleaned.substringAfterLast('/')) {
            val base = cleaned.substringBeforeLast('.', cleaned)
            return "$base.$extension"
        }

        return cleaned
    }

    private fun extensionFromMimeType(mimeType: String): String {
        val normalized = normalizeMimeType(mimeType)
        return when (normalized) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized).orEmpty()
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
            else -> "video/mp4"
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
        private const val APP_FOLDER_NAME = "sss-vid"

        private fun downloadsRelativePath(): String {
            return "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER_NAME"
        }

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
