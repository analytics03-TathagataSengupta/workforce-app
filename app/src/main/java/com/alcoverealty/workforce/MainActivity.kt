package com.alcoverealty.workforce

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        // Links on these domains open INSIDE the app; anything else opens in the phone browser.
        // If your login uses Google/Microsoft SSO, add that domain here (e.g. "accounts.google.com").
        private val INTERNAL_HOSTS = listOf("alcoverealty.in")
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View

    // ---- File upload state ----
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    // ---- Location (geolocation) state ----
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private var lastBackPress = 0L

    // Receives the file(s) picked by the user (gallery / files / camera)
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val clip = data?.clipData
                uris = when {
                    clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri } // multiple files
                    data?.data != null -> arrayOf(data.data!!)                        // single file
                    (cameraFile?.length() ?: 0L) > 0L -> arrayOf(cameraUri!!)         // camera photo
                    else -> null
                }
            }
            filePathCallback?.onReceiveValue(uris) // must ALWAYS be called, even with null
            filePathCallback = null
        }

    // Asks location permission when the website requests it
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val granted = res.values.any { it }
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
            geoOrigin = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)

        setupWebView()
        setupSwipeRefresh()
        setupBackButton()

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        // Restore page after process restart, else open the login page
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(getString(R.string.home_url))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Keep login session (cookies) between app launches
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true          // the web app needs JS
            domStorageEnabled = true          // localStorage / sessionStorage
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false           // security: no local file access
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            // Server can detect app traffic via this tag in the User-Agent
            userAgentString = "$userAgentString AlcoveWorkforceApp/${packageManager.getPackageInfo(packageName, 0).versionName}"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush() // save cookies to disk
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // Only show error screen if the MAIN page failed (not an image/script)
                if (request?.isForMainFrame == true) {
                    errorView.visibility = View.VISIBLE
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                return openFileChooser(callback, params)
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                if (fine == PackageManager.PERMISSION_GRANTED) {
                    callback?.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    locationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        }
    }

    /** Returns true if WE handled the url (outside app), false = let WebView load it. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: ""

        if (scheme == "http" || scheme == "https") {
            val internal = INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
            if (internal) return false   // open inside the app
        }
        // tel:, mailto:, whatsapp:, other websites -> open with the right phone app
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Shows a chooser with Camera + Gallery/Files for <input type="file"> on the website. */
    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        filePathCallback?.onReceiveValue(null) // cancel any previous pending request
        filePathCallback = callback
        if (params == null) return false

        val contentIntent = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        // Offer camera only when the field accepts images
        val extraIntents = mutableListOf<Intent>()
        val acceptsImages = params.acceptTypes.isEmpty() ||
                params.acceptTypes.any { it.isBlank() || it.startsWith("image") || it == "*/*" }
        cameraFile = null
        cameraUri = null
        if (acceptsImages) {
            try {
                val dir = File(cacheDir, "camera").apply { mkdirs() }
                val file = File.createTempFile("IMG_", ".jpg", dir)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                cameraFile = file
                cameraUri = uri
                extraIntents += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) { /* camera optional */ }
        }

        val chooser = Intent.createChooser(contentIntent, getString(R.string.select_file)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
        }
        return try {
            fileChooserLauncher.launch(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            filePathCallback = null
            false
        }
    }

    /** Downloads files (PDF/Excel reports etc.) using Android's DownloadManager. */
    private fun downloadFile(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED

        // blob:/data: links or old-Android without permission -> let the browser handle it
        if (!url.startsWith("http") || needsLegacyPermission) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url)) // keep login
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.brand_blue, R.color.brand_red)
        swipeRefresh.setOnRefreshListener { webView.reload() }
        // Only allow pull-to-refresh when page is scrolled to the very top
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()                       // go to previous web page
                } else if (System.currentTimeMillis() - lastBackPress < 2000) {
                    finish()                               // double-back to exit
                } else {
                    lastBackPress = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, getString(R.string.press_back_again), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() { super.onResume(); webView.onResume() }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
