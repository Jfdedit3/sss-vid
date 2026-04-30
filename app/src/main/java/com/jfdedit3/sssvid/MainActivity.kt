package com.jfdedit3.sssvid

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingDownload: DownloadData? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDownload?.let { startDownload(it) }
        } else {
            Toast.makeText(this, "Permission de téléchargement refusée", Toast.LENGTH_LONG).show()
        }
        pendingDownload = null
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
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val data = DownloadData(url, userAgent, contentDisposition, mimeType, fileName)

            if (needsLegacyStoragePermission()) {
                pendingDownload = data
                requestLegacyStoragePermission()
            } else {
                startDownload(data)
            }
        })
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

    private fun startDownload(data: DownloadData) {
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

    data class DownloadData(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
        val fileName: String
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
