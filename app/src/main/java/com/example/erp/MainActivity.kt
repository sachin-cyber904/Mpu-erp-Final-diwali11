package com.example.erp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.animation.AnimationUtils
import android.webkit.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var isAlive: Boolean = false
    }

    private val printGuardJs = """
        (function() {
            if (window.__printGuardAttached) return;
            window.__printGuardAttached = true;

            function hookWindow(win) {
                try { win.print = function() { AndroidBridge.triggerPrint(); }; } catch (e) {}
                try {
                    if (win.document && win.document.execCommand) {
                        var origExecCommand = win.document.execCommand.bind(win.document);
                        win.document.execCommand = function(cmd) {
                            // ✅ PRINT FIX — jQuery.print calls
                            // execCommand("print") FIRST and only falls back to
                            // win.print() if that returns false. Android WebView's
                            // real execCommand("print") returns truthy without
                            // actually printing anything, silently swallowing the
                            // whole action before our win.print() hook above ever
                            // runs. Forcing it to report failure here makes every
                            // jQuery.print call (fees dues, id card, receipts,
                            // certificates, etc.) fall through to our hook.
                            if (typeof cmd === 'string' && cmd.toLowerCase() === 'print') {
                                return false;
                            }
                            return origExecCommand.apply(win.document, arguments);
                        };
                    }
                } catch (e) {}
            }

            hookWindow(window);

            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(m) {
                    if (!m.addedNodes) return;
                    m.addedNodes.forEach(function(node) {
                        if (node.tagName === 'IFRAME') {
                            var tries = 0;
                            var timer = setInterval(function() {
                                tries++;
                                try { if (node.contentWindow) hookWindow(node.contentWindow); } catch (e) {}
                                if (tries > 30) clearInterval(timer);
                            }, 10);
                            node.addEventListener('load', function() {
                                try { if (node.contentWindow) hookWindow(node.contentWindow); } catch (e) {}
                            });
                        }
                    });
                });
            });

            // Observer needs a document to attach to — guard for very early execution
            // (this script can now run at document-start, before documentElement exists).
            if (document.documentElement) {
                observer.observe(document.documentElement, { childList: true, subtree: true });
            } else {
                document.addEventListener('DOMContentLoaded', function() {
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                });
            }
        })();
    """.trimIndent()

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var spinnerImage: ImageView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var canScrollUpJs = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "Camera/Mic permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
            geoOrigin = null
            if (!granted) {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val spinnerTimeoutHandler = Handler(Looper.getMainLooper())
    private val spinnerTimeoutRunnable = Runnable { hideSpinner() }
    private var spinnerCurrentlyVisible = false
    private fun showSpinner() {
        loadingLayout.visibility = View.VISIBLE
        spinnerImage.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.spinner_rotate)
        )
        if (!spinnerCurrentlyVisible) {
            spinnerCurrentlyVisible = true
            spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
            spinnerTimeoutHandler.postDelayed(spinnerTimeoutRunnable, 12000)
        }
    }

    private val autoLoginAttempted = java.util.Collections.synchronizedSet(mutableSetOf<LoginType>())

    private fun setLastLoggedInType(type: LoginType) {
        getSharedPreferences("app_session_prefs", MODE_PRIVATE).edit()
            .putString("last_logged_in_type", type.name)
            .apply()
    }
    private fun getLastLoggedInType(): LoginType? {
        val name = getSharedPreferences("app_session_prefs", MODE_PRIVATE)
            .getString("last_logged_in_type", null) ?: return null
        return try { LoginType.valueOf(name) } catch (e: Exception) { null }
    }
    private fun clearLastLoggedInType() {
        getSharedPreferences("app_session_prefs", MODE_PRIVATE).edit()
            .remove("last_logged_in_type")
            .apply()
    }

    private var pendingLoginType: LoginType? = null
    private var pendingEmail: String? = null
    private var pendingPassword: String? = null

    private fun saveLastLoginType(type: LoginType) {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
            .putString("last_login_type", type.name).apply()
    }
    private fun getLastLoginType(): LoginType? {
        val name = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("last_login_type", null) ?: return null
        return try { LoginType.valueOf(name) } catch (e: Exception) { null }
    }
    private fun clearLastLoginType() {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
            .remove("last_login_type").apply()
    }

    private var initialAutoLoginTried = false

    private var lastLoadFailed = false
    private var autoRetryCount = 0
    private val MAX_AUTO_RETRIES = 2

    private val postFinishHandler = Handler(Looper.getMainLooper())
    private var postFinishHideRunnable: Runnable? = null

    private fun hideSpinner() {
        loadingLayout.visibility = View.GONE
        spinnerImage.clearAnimation()
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
        spinnerCurrentlyVisible = false
        if (swipeRefresh.isRefreshing) {
            swipeRefresh.isRefreshing = false
        }
    }

    private fun saveBase64ToDownloads(base64Data: String, filename: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Could not create download entry")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, filename)
                FileOutputStream(file).use { it.write(bytes) }
            }
            runOnUiThread {
                Toast.makeText(this, "Downloaded: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun printCurrentPage() {
        if (!::webView.isInitialized) return
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(this, "Printing not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val jobName = "ID-Card-${System.currentTimeMillis()}"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }
    private val documentExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar")

    private fun isDocumentUrl(url: String): Boolean {
        val path = try { Uri.parse(url).path ?: "" } catch (e: Exception) { "" }
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in documentExtensions
    }

    private fun downloadDocument(url: String) {
        try {
            val fileName = URLUtil.guessFileName(url, null, null)
            val request = DownloadManager.Request(Uri.parse(url))
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
            request.setDescription("Downloading file...")
            request.setTitle(fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectPrintGuard(view: WebView?) {
        view?.evaluateJavascript(printGuardJs, null)
    }

    private fun loginTypeForUrl(url: String?): LoginType? {
        if (url == null) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = uri?.path ?: url
        return when {
            path.contains("/student/login") -> LoginType.STUDENT
            path.contains("/admin/login") -> LoginType.ADMIN
            else -> null
        }
    }

    private fun logoutTypeForUrl(url: String?): LoginType? {
        if (url == null) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = (uri?.path ?: url).lowercase()
        return when {
            path.contains("/student/logout") -> LoginType.STUDENT
            path.contains("/admin/logout") -> LoginType.ADMIN
            else -> null
        }
    }

    private fun clearCredentialsForLogout(type: LoginType?) {
        if (type != null) {
            CredentialStore.clear(this@MainActivity, type)
            autoLoginAttempted.remove(type)
        } else {
            CredentialStore.clear(this@MainActivity, LoginType.ADMIN)
            CredentialStore.clear(this@MainActivity, LoginType.STUDENT)
            autoLoginAttempted.clear()
        }
        clearLastLoginType()
        clearLastLoggedInType()
    }
    private var cookieWipePending = false

    private fun performLogoutCleanup(type: LoginType?) {
        clearCredentialsForLogout(type)
        runOnUiThread {
            canScrollUpJs = false
            if (::swipeRefresh.isInitialized) {
                swipeRefresh.isRefreshing = false
            }
        }
        cookieWipePending = true
    }

    private fun injectAutoLogin(view: WebView?, type: LoginType) {
        val savedEmail = CredentialStore.getEmail(this, type).replace("\\", "\\\\").replace("'", "\\'")
        val savedPassword = CredentialStore.getPassword(this, type).replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                var userField = document.querySelector('#email') || document.querySelector('#identifier') ||
                                 document.querySelector('input[name="email"]') || document.querySelector('input[name="identifier"]') ||
                                 document.querySelector('input[name="username"]') ||
                                 document.querySelector('input[type="text"][placeholder*="Login" i]') ||
                                 document.querySelector('form input[type="text"]') ||
                                 document.querySelector('form input[type="email"]');
                var passField = document.querySelector('#password') || document.querySelector('input[name="password"]');

                if (!userField || !passField) {
                    return;
                }

                AndroidBridge.markAutoLoginAttempted('${type.name}');
                userField.value = '$savedEmail';
                passField.value = '$savedPassword';

                var form = document.querySelector('form');
                var btn = document.querySelector('button[type="submit"]') || document.querySelector('input[type="submit"]');
                if (btn) {
                    btn.click();
                } else if (form && form.requestSubmit) {
                    form.requestSubmit();
                } else if (form) {
                    form.submit();
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun injectAutoLoginTypeSelector(view: WebView?, type: LoginType) {
        val cssClass = if (type == LoginType.STUDENT) "btn-student" else "btn-admin"
        val label = if (type == LoginType.STUDENT) "student" else "admin"
        val js = """
        (function() {
            var hasFields = document.querySelector('#email') || document.querySelector('#identifier') ||
                             document.querySelector('input[name="email"]') || document.querySelector('input[name="identifier"]') ||
                             document.querySelector('input[name="password"]');
            if (hasFields) return;

            var byClass = document.querySelector('.$cssClass');
            if (byClass) { byClass.click(); return; }

            var candidates = document.querySelectorAll('a, button');
            for (var i = 0; i < candidates.length; i++) {
                var el = candidates[i];
                var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                if (text.indexOf('$label') !== -1) {
                    el.click();
                    return;
                }
            }
        })();
    """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun injectAutoFill(view: WebView?, type: LoginType) {
        if (!CredentialStore.hasSavedCredentials(this, type)) return

        val savedEmail = CredentialStore.getEmail(this, type).replace("\\", "\\\\").replace("'", "\\'")
        val savedPassword = CredentialStore.getPassword(this, type).replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                var userField = document.querySelector('#email') || document.querySelector('#identifier') ||
                                 document.querySelector('input[name="email"]') || document.querySelector('input[name="identifier"]') ||
                                 document.querySelector('input[name="username"]') ||
                                 document.querySelector('input[type="text"][placeholder*="Login" i]') ||
                                 document.querySelector('form input[type="text"]') ||
                                 document.querySelector('form input[type="email"]');
                var passField = document.querySelector('#password') || document.querySelector('input[name="password"]');
                if (userField) userField.value = '$savedEmail';
                if (passField) passField.value = '$savedPassword';
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun injectFormCapture(view: WebView?, type: LoginType) {
        val js = """
            (function() {
                var form = document.querySelector('form');
                if (form && !form.dataset.bridgeAttached) {
                    form.dataset.bridgeAttached = 'true';
                    form.addEventListener('submit', function() {
                        var userField = document.querySelector('#email') || document.querySelector('#identifier') ||
                                         document.querySelector('input[name="email"]') || document.querySelector('input[name="identifier"]') ||
                                         document.querySelector('input[name="username"]') ||
                                         document.querySelector('input[type="text"][placeholder*="Login" i]') ||
                                         document.querySelector('form input[type="text"]') ||
                                         document.querySelector('form input[type="email"]');
                        var passField = document.querySelector('#password') || document.querySelector('input[name="password"]');
                        if (userField && passField && userField.value && passField.value) {
                            AndroidBridge.onLoginSubmit('${type.name}', userField.value, passField.value);
                        }
                    });
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun injectLogoutGuard(view: WebView?) {
        val js = """
        (function() {
            if (window.__logoutGuardAttached) return;
            window.__logoutGuardAttached = true;
            document.addEventListener('click', function(e) {
                var el = e.target;
                for (var i = 0; i < 6 && el; i++) {
                    var cls = (el.className || '').toString().toLowerCase();
                    var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                    var href = (el.getAttribute && el.getAttribute('href') || '').toLowerCase();
                    if (cls.indexOf('dud-logout') !== -1 ||
                        text === 'logout' || text === 'log out' || text === 'sign out' ||
                        href.indexOf('logout') !== -1) {
                        AndroidBridge.onLogoutClicked();
                        return;
                    }
                    el = el.parentElement;
                }
            }, true);
        })();
    """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun injectNestedScrollGuard(view: WebView?) {
        val js = """
            (function() {
                if (window.__ptrGuardAttached) return;
                window.__ptrGuardAttached = true;

                function report(scrollTop) {
                    AndroidBridge.setCanScrollUp(scrollTop > 0);
                }

                document.addEventListener('scroll', function(e) {
                    var t = e.target;
                    var scrollTop;
                    if (t === document) {
                        scrollTop = (document.scrollingElement || document.documentElement).scrollTop;
                    } else {
                        scrollTop = t.scrollTop;
                    }
                    report(scrollTop);
                }, true);

                window.addEventListener('scroll', function() {
                    report(window.scrollY);
                }, true);

                // ✅ report current state immediately on attach, so a page that loads
                // already scrolled (rare) or a stale reading gets corrected right away
                // instead of waiting for the user's first scroll gesture.
                report((document.scrollingElement || document.documentElement).scrollTop);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }


    private fun injectLoginScrollFix(view: WebView?) {
        val js = """
        (function() {
            if (window.__loginScrollFixAttached) return;
            window.__loginScrollFixAttached = true;
            var style = document.createElement('style');
            style.textContent = 
                'html, body { height: auto !important; min-height: 100% !important; overflow-y: auto !important; }' +
                '.auth-wrapper { position: relative !important; min-height: 100vh !important; display: flex !important; align-items: center !important; justify-content: center !important; padding: 24px 0 !important; box-sizing: border-box !important; }' +
                '.auth-wrapper .auth-content { position: static !important; left: auto !important; top: auto !important; transform: none !important; margin: 0 auto !important; max-width: 92vw !important; }';
            document.head.appendChild(style);
        })();
    """.trimIndent()
        view?.evaluateJavascript(js, null)
    }




    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingLayout = findViewById(R.id.loadingLayout)
        spinnerImage = findViewById(R.id.spinnerImage)

        showSpinner()
        requestCameraAndMicPermissions()

        if (!isInternetAvailable()) {
            startActivity(Intent(this, NoInternetActivity::class.java))
            finish()
            return
        }
        isAlive = true

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(true)
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            saveFormData = false
        }


        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, printGuardJs, setOf("*"))
        }

        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO

        webView.scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
        webView.overScrollMode = WebView.OVER_SCROLL_ALWAYS
        webView.isScrollbarFadingEnabled = true
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true

        swipeRefresh.setOnChildScrollUpCallback { _, _ -> canScrollUpJs }

        swipeRefresh.setColorSchemeColors(android.graphics.Color.WHITE)
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            android.graphics.Color.parseColor("#001F5B")
        )

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onLoginSubmit(typeName: String, email: String, password: String) {
                if (email.isNotBlank() && password.isNotBlank()) {
                    val type = try {
                        LoginType.valueOf(typeName)
                    } catch (e: Exception) {
                        null
                    }
                    if (type != null) {
                        runOnUiThread {
                            pendingLoginType = type
                            pendingEmail = email
                            pendingPassword = password
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onLogoutClicked() {
                performLogoutCleanup(getLastLoggedInType())
            }

            @JavascriptInterface
            fun markAutoLoginAttempted(typeName: String) {
                runOnUiThread {
                    val type = try { LoginType.valueOf(typeName) } catch (e: Exception) { null }
                    if (type != null) autoLoginAttempted.add(type)
                }
            }

            @JavascriptInterface
            fun setCanScrollUp(canScrollUp: Boolean) {
                runOnUiThread {
                    canScrollUpJs = canScrollUp
                }
            }

            @JavascriptInterface
            fun triggerPrint() {
                runOnUiThread {
                    printCurrentPage()
                }
            }
            @JavascriptInterface
            fun saveBase64File(base64Data: String, filename: String, mimeType: String) {
                Thread { saveBase64ToDownloads(base64Data, filename, mimeType) }.start()
            }
        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            private var isMainFrameFinished = false
            private var currentMainFrameLoadFailed = false

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val destUrl = request?.url.toString()
                val scheme = request?.url?.scheme

                val logoutType = logoutTypeForUrl(destUrl)
                if (logoutType != null) {
                    performLogoutCleanup(logoutType)
                }
                if (scheme == "http" || scheme == "https") {
                    if (isDocumentUrl(destUrl)) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Could not open link", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    return false
                }

                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request?.url))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isMainFrameFinished = false
                currentMainFrameLoadFailed = false
                canScrollUpJs = false
                postFinishHideRunnable?.let { postFinishHandler.removeCallbacks(it) }
                showSpinner()
                injectPrintGuard(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isMainFrameFinished) return
                if (cookieWipePending) {
                    cookieWipePending = false
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
                if (currentMainFrameLoadFailed) {
                    return
                }
                isMainFrameFinished = true
                lastLoadFailed = false
                autoRetryCount = 0
                injectNestedScrollGuard(view)
                injectLogoutGuard(view)
                injectPrintGuard(view)

                val currentType = loginTypeForUrl(url)

                val willAutoSubmit = currentType != null &&
                        CredentialStore.hasSavedCredentials(this@MainActivity, currentType) &&
                        !autoLoginAttempted.contains(currentType)

                postFinishHideRunnable = Runnable {
                    hideSpinner()
                    swipeRefresh.isRefreshing = false
                    CookieManager.getInstance().flush()
                }
                postFinishHandler.postDelayed(
                    postFinishHideRunnable!!,
                    if (willAutoSubmit) 6000L else 1500L
                )

                if (currentType != null) {
                    initialAutoLoginTried = true
                    injectLoginScrollFix(view)

                    if (pendingLoginType == currentType) {
                        pendingLoginType = null
                        pendingEmail = null
                        pendingPassword = null
                    }

                    view?.evaluateJavascript("""
                        (function() {
                            var cb = document.getElementById('remember');
                            if (cb && !cb.checked) { cb.checked = true; }
                        })();
                    """.trimIndent(), null)

                    injectFormCapture(view, currentType)
                    if (willAutoSubmit) {
                        injectAutoLogin(view, currentType)
                    } else {
                        injectAutoFill(view, currentType)
                    }
                } else {
                    if (!initialAutoLoginTried) {
                        initialAutoLoginTried = true
                    }
                    val pType = pendingLoginType
                    val pEmail = pendingEmail
                    val pPass = pendingPassword
                    if (pType != null && pEmail != null && pPass != null) {
                        CredentialStore.save(this@MainActivity, pType, pEmail, pPass)
                        saveLastLoginType(pType)
                        setLastLoggedInType(pType)
                        autoLoginAttempted.remove(pType)
                    }
                    pendingLoginType = null
                    pendingEmail = null
                    pendingPassword = null
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    currentMainFrameLoadFailed = true
                    lastLoadFailed = true
                    if (!isInternetAvailable()) {
                        startActivity(Intent(this@MainActivity, NoInternetActivity::class.java))
                        return
                    }
                    swipeRefresh.isRefreshing = false
                    if (autoRetryCount >= MAX_AUTO_RETRIES) {
                        startActivity(Intent(this@MainActivity, NoInternetActivity::class.java))
                        return
                    }
                    autoRetryCount++
                    view?.postDelayed({
                        if (isInternetAvailable()) {
                            view.reload()
                        } else {
                            startActivity(Intent(this@MainActivity, NoInternetActivity::class.java))
                        }
                    }, 800)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    hideSpinner()
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }
            // 📍 Website jab location maange (navigator.geolocation) tab ye call hota hai
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (hasLocationPermission()) {
                    callback?.invoke(origin, true, false)
                } else {
                    geoCallback?.invoke(geoOrigin, false, false)
                    geoCallback = callback
                    geoOrigin = origin
                    locationPermissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            override fun onGeolocationPermissionsHidePrompt() {
                geoCallback = null
                geoOrigin = null
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val popupWebView = WebView(this@MainActivity)   // 👈 "this" ki jagah "this@MainActivity"
                popupWebView.settings.javaScriptEnabled = true
                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        popupView: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val popupUrl = request?.url?.toString()
                        if (popupUrl != null) {
                            webView.loadUrl(popupUrl)
                        }
                        popupWebView.destroy()
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }


            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select File"))
                return true
            }

            override fun onJsAlert(
                view: WebView?, url: String?,
                message: String?, result: JsResult?
            ): Boolean {
                result?.confirm()
                return false
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("blob:")) {
                val safeMime = if (mimeType.isNullOrBlank()) "application/octet-stream" else mimeType
                val js = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64 = reader.result.split(',')[1];
                                AndroidBridge.saveBase64File(base64, '$fileName', '$safeMime');
                            };
                            reader.readAsDataURL(xhr.response);
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.setDescription("Downloading file...")
                request.setTitle(fileName)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://codemind25.com/")
        }

        swipeRefresh.setOnRefreshListener {
            if (isInternetAvailable()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                startActivity(Intent(this, NoInternetActivity::class.java))
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Exit MPU ERP")
                    .setMessage("Are you sure you want to exit?")
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .setNegativeButton("Stay") { dialog, _ -> dialog.dismiss() }
                    .setCancelable(false)
                    .show()
                    .also { dialog ->
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                            ?.setTextColor(android.graphics.Color.parseColor("#001F5B"))
                        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                            ?.setTextColor(android.graphics.Color.parseColor("#C9A84C"))
                    }
            }
        }

    }

    private fun requestCameraAndMicPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results: Array<Uri>? = when {
                    data?.clipData != null -> {
                        val count = data.clipData!!.itemCount
                        Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (::webView.isInitialized) {
            if (lastLoadFailed) {
                if (isInternetAvailable()) {
                    lastLoadFailed = false
                    showSpinner()
                    webView.reload()
                }
            } else if (spinnerCurrentlyVisible) {
                spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
                spinnerTimeoutHandler.postDelayed(spinnerTimeoutRunnable, 12000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
        postFinishHideRunnable?.let { postFinishHandler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
        postFinishHideRunnable?.let { postFinishHandler.removeCallbacks(it) }
        isAlive = false
    }

}