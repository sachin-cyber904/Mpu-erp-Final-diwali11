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
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        // true while MainActivity is alive (created + past the internet check), even if
        // NoInternetActivity is currently on top of it. Lets NoInternetActivity's Retry
        // just return to this same instance (same page, same session) instead of always
        // creating a fresh MainActivity that reloads the site root.
        @Volatile
        var isAlive: Boolean = false
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var spinnerImage: ImageView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ✅ PULL-TO-REFRESH GUARD — true whenever JS tells us ANY scrollable area on the page
    // (outer page, a navbar drawer, a "Hostel" panel, or any future panel) is currently
    // scrolled down (scrollTop > 0). SwipeRefreshLayout queries this live via
    // setOnChildScrollUpCallback on every touch move, so there's no stale-flag race condition.
    private var canScrollUpJs = false

    // ✅ CAMERA & MIC PERMISSION REQUEST
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "Camera/Mic permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // ✅ CHECK INTERNET
    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ✅ SHOW SPINNER
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
            spinnerTimeoutHandler.postDelayed(spinnerTimeoutRunnable, 12000) // 12s max, from first show
        }
    }

    // ✅ AUTO-LOGIN — tracks which login types we've already silently auto-submitted this
    // app session. Synchronized because logout cleanup now touches this directly from the
    // JS-bridge call thread (not queued via runOnUiThread) to close a race with the very
    // next page load — see performLogoutCleanup / clearCredentialsForLogout below.
    private val autoLoginAttempted = java.util.Collections.synchronizedSet(mutableSetOf<LoginType>())

    // ✅ Tracks which login type is CURRENTLY logged in, persisted across app restarts (not
    // just in-memory) — set the moment a login is confirmed successful, and consulted on
    // logout (both the URL-based path and the JS click-fallback) so we flush EXACTLY that
    // one type's credentials instead of guessing / clearing both.
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

    // Nothing is written to disk until success is confirmed — this way a wrong/old password
    // typed by mistake never overwrites a previously-saved good one.
    private var pendingLoginType: LoginType? = null
    private var pendingEmail: String? = null
    private var pendingPassword: String? = null

    // ✅ Remembers which login type (Student/Admin) the user last successfully logged in
    // with, persisted across app restarts. Used right after app launch to know WHICH button
    // to auto-click on a "choose Student or Admin" selection page.
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

    // ✅ Confines the risky "search this page for a Student/Admin button and click it"
    // behavior to ONLY the very first page shown after the app launches.
    private var initialAutoLoginTried = false

    private var lastLoadFailed = false
    private var autoRetryCount = 0
    private val MAX_AUTO_RETRIES = 2

    // ✅ Cancellable "settle then hide spinner" timer. Previously this was a bare
    // webView.postDelayed(..., 300) with no reference kept — so on a CHAINED navigation
    // (selection page auto-clicked → credential page auto-filled+submitted → homepage, all
    // within ~150-300ms of each other) the FIRST page's delayed hide would still fire ~300ms
    // after ITS finish, landing in the middle of the NEXT page's load and hiding the spinner
    // early. That's exactly what exposed the credential page / mid-load flash the user kept
    // seeing. Now every onPageStarted cancels any pending hide from the previous page, so the
    // spinner only ever comes down once the LATEST page in the chain has actually settled.
    private val postFinishHandler = Handler(Looper.getMainLooper())
    private var postFinishHideRunnable: Runnable? = null

    // ✅ HIDE SPINNER
    private fun hideSpinner() {
        loadingLayout.visibility = View.GONE
        spinnerImage.clearAnimation()
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
        spinnerCurrentlyVisible = false
        if (swipeRefresh.isRefreshing) {
            swipeRefresh.isRefreshing = false
        }
    }

    // ✅ BLOB DOWNLOAD FIX — pages that generate files client-side (e.g. jsPDF's
    // `pdf.save("Id-card.pdf")`) create a Blob and trigger it via a hidden
    // <a download href="blob:...">. Android's DownloadManager can only handle http/https
    // URIs, so handing it a blob: URL throws immediately ("could not handle URI"). The actual
    // bytes are fetched back out via JS (see setDownloadListener below) and written here.
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

    // ✅ ID CARD FIX — WebView has no built-in window.print() support, so the site's
    // jQuery.print plugin silently does nothing when the "ID Card" button is tapped. This
    // triggers Android's native Print Framework instead, which lets the user save the card
    // as a PDF (acts as the "download") or send it to an actual printer.
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

    // ✅ jQuery.print (used by the ID card page) builds a hidden <iframe> and calls print()
    // on the IFRAME's window, not the top-level page window — so a plain "override
    // window.print" only catches direct callers and misses this plugin entirely. This watches
    // for any iframe getting inserted and hooks ITS contentWindow.print too, as early as
    // possible after the node appears (a short poll, since document.write() into the iframe
    // happens right after insertion and could otherwise race the hook).
    private fun injectPrintGuard(view: WebView?) {
        val js = """
            (function() {
                if (window.__printGuardAttached) return;
                window.__printGuardAttached = true;

                function hookWindow(win) {
                    try { win.print = function() { AndroidBridge.triggerPrint(); }; } catch (e) {}
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
                observer.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ✅ exact real routes
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

    // ✅ THE CRITICAL PART — wipes CredentialStore + the "who's logged in" prefs.
    // Deliberately NOT wrapped in runOnUiThread: SharedPreferences reads/writes (which is
    // what CredentialStore and the last-logged-in-type prefs use) are documented thread-safe
    // from any thread. This matters because the JS logout-click detector calls into
    // AndroidBridge SYNCHRONOUSLY — the JS thread blocks until this Kotlin call returns —
    // and only THEN does the browser continue on to the link's default navigation (or the
    // site's own AJAX logout handler). If we instead posted this work to the UI thread via
    // runOnUiThread (as before), the method returned immediately, JS carried on to navigate
    // to the next page, and that next page's onPageFinished could run its auto-login check
    // BEFORE the queued cleanup actually executed — a real race that let auto-login silently
    // fire again right after logout. Doing it synchronously here closes that race.
    private fun clearCredentialsForLogout(type: LoginType?) {
        if (type != null) {
            // we know exactly which account logged out — clear only that one, leave the
            // other type's saved credentials untouched.
            CredentialStore.clear(this@MainActivity, type)
            autoLoginAttempted.remove(type)
        } else {
            // we genuinely don't know which type was active (shouldn't normally happen once
            // setLastLoggedInType is being set on every successful login) — fall back to
            // clearing both, since leaving stale credentials behind is the worse outcome.
            CredentialStore.clear(this@MainActivity, LoginType.ADMIN)
            CredentialStore.clear(this@MainActivity, LoginType.STUDENT)
            autoLoginAttempted.clear()
        }
        clearLastLoginType()
        clearLastLoggedInType()
    }

    // ✅ Central place to actually wipe credentials for a logout — used by BOTH the
    // URL-based logout detector and the JS click-fallback detector, so the "which type do
    // we clear" decision lives in one spot instead of being duplicated/out-of-sync.
    private fun performLogoutCleanup(type: LoginType?) {
        clearCredentialsForLogout(type)
        // Cookie clearing isn't part of the auto-login race (auto-login only checks
        // CredentialStore, not cookies), so it's fine for this part to happen slightly
        // later on the UI thread, which is where CookieManager is meant to be driven from.
        runOnUiThread {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    // ✅ SILENT AUTO-LOGIN — fills fields and submits IMMEDIATELY (no artificial delay)
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

    // ✅ Selection page has real class names, use them primarily. Only called when explicitly
    // invoked (currently unused since the selection page is always shown manually — kept for
    // future use / manual invocation if needed).
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

    // ✅ AUTO-FILL SAVED EMAIL/PASSWORD ON LOGIN PAGE (admin/student kept separate)
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

    // ✅ CAPTURE EMAIL/PASSWORD WHEN LOGIN FORM IS SUBMITTED
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

    // ✅ match by class "dud-logout" (real button), plus keep old checks as fallback
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

    // ✅ NESTED-SCROLL GUARD (v3 — scroll-event based, not touch-guess based)
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
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            saveFormData = false
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

            // ✅ Called from the JS logout-click detector (see injectLogoutGuard). Now clears
            // ONLY the currently-logged-in type (tracked via setLastLoggedInType, set the
            // moment a login is confirmed) instead of wiping both accounts blindly.
            @JavascriptInterface
            fun onLogoutClicked() {
                // NOT wrapped in runOnUiThread on purpose — see clearCredentialsForLogout's
                // comment. The JS click handler is blocked on this call, so credentials are
                // guaranteed cleared before it returns control to the page (and any
                // resulting navigation).
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

            // ✅ Called from injectPrintGuard when the page (or its jQuery.print iframe)
            // calls print() — hands off to Android's native Print Framework since WebView
            // itself has no print support.
            @JavascriptInterface
            fun triggerPrint() {
                runOnUiThread {
                    printCurrentPage()
                }
            }
            // ✅ Called from setDownloadListener's blob-fetch script with the file's actual
            // bytes (base64-encoded), since the blob: URL itself can't be handed to
            // DownloadManager.
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

                // ✅ Explicit logout via real navigation → clear saved credentials for that
                // login type only (security), same cleanup path as the JS click-fallback.
                val logoutType = logoutTypeForUrl(destUrl)
                if (logoutType != null) {
                    performLogoutCleanup(logoutType)
                }

                // ✅ PAYMENT FIX — normal http/https navigation ko WebView khud natively handle
                // karne do (return false). Manually loadUrl(destUrl) call karna har navigation
                // ko GET me convert kar deta tha, jisse fee-payment ke auto-submit POST forms
                // (CCAvenue encRequest/access_code, pay-process fields) ka data hi drop ho jaata
                // tha — isiliye payment browser me chalta tha par app me nahi.
                if (scheme == "http" || scheme == "https") {
                    return false
                }

                // ✅ Non-web schemes (upi://, intent://, tel:, mailto:) — kuch bank/payment
                // pages UPI app handoff ya OTP app ke liye ye fire karte hain. System ko
                // handle karne do.
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
                // cancel any still-pending "hide spinner" from a previous page in a chained
                // navigation (selector page → credential page → home) so it can't fire in
                // the middle of THIS new page's load and expose it before it's ready
                postFinishHideRunnable?.let { postFinishHandler.removeCallbacks(it) }
                showSpinner()
                // ✅ Attach the print hook as early as possible — before this page's own
                // scripts (e.g. the ID card's jQuery.print call) have a chance to run.
                injectPrintGuard(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isMainFrameFinished) return
                if (currentMainFrameLoadFailed) {
                    return
                }
                isMainFrameFinished = true
                lastLoadFailed = false
                autoRetryCount = 0
                injectNestedScrollGuard(view)
                injectLogoutGuard(view)
                injectPrintGuard(view)

                postFinishHideRunnable = Runnable {
                    hideSpinner()
                    swipeRefresh.isRefreshing = false
                    CookieManager.getInstance().flush()
                }
                postFinishHandler.postDelayed(postFinishHideRunnable!!, 1500)

                val currentType = loginTypeForUrl(url)
                if (currentType != null) {
                    initialAutoLoginTried = true

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
                    if (CredentialStore.hasSavedCredentials(this@MainActivity, currentType) &&
                        !autoLoginAttempted.contains(currentType)) {
                        // ✅ credentials already saved for this type → fill + submit instantly,
                        // no visible credential page (spinner stays up through the chain)
                        injectAutoLogin(view, currentType)
                    } else {
                        injectAutoFill(view, currentType)
                    }
                } else {
                    // ✅ Always show the Student/Admin selection page — let user pick manually
                    if (!initialAutoLoginTried) {
                        initialAutoLoginTried = true
                    }
                    // ✅ Navigated AWAY from a login page — a pending submit means login
                    // SUCCEEDED, so persist it now, AND record it as the currently-logged-in
                    // type so a later logout knows exactly which one to flush.
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

            // ✅ PAYMENT GATEWAY FIX — bank ka OTP/3D-Secure verification page window.open() /
            // target="_blank" se khulta hai. Bina isके WebView popup ko silently drop kar deta
            // hai, isliye "Pay Now" ke baad kuch nahi hota tha. Ye popup ka content SAME
            // visible WebView me route kar deta hai.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = webView
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
                // ✅ DownloadManager can't take a blob: URL — pull the actual bytes back out
                // via JS (still running in the same page/document that owns the blob) and
                // hand them to saveBase64File instead.
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

    } // ✅ END OF onCreate

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

} // ✅ END OF CLASS