package com.kidslauncher.mdm.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.PlaySignInRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlaySignIn"

/** Google's Android-setup sign-in page - once signed in there, Google sets an `oauth_token`
 * cookie that can be swapped (once) for a long-lived Play token. Same page Aurora Store and EFF's
 * apkeep use. */
private const val EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup"
private const val GOOGLE_ACCOUNTS_URL = "https://accounts.google.com"
private const val TOKEN_COOKIE = "oauth_token"

/**
 * "Sign in to Google Play" (server v0.24.0+), opened from the PIN-gated Settings screen: the parent
 * signs in to their spare Google account on Google's own page here, and this hands the resulting
 * one-time sign-in code to kid-phone-server, which keeps only the long-lived token it swaps it
 * for (see the server's `device_api::play_signin`). The password only ever goes to Google. The
 * server only accepts this while the parent has "Sign in using a phone" open on its Google Play
 * settings page.
 *
 * The cookie is read from this app's own WebView cookie store - something only an app can do; a
 * website (including the parent site as a PWA) can't read another site's cookies. Every Google
 * cookie and web storage entry is wiped before and after, so the kid's device isn't left signed in
 * to Google anywhere.
 */
class PlaySignInActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var sent = false

    /** onPageFinished doesn't always fire after Google's script-driven steps, so also poll. */
    private val pollForToken = object : Runnable {
        override fun run() {
            if (sent) return
            checkForToken()
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.play_signin_title)

        status = TextView(this).apply {
            setText(R.string.play_signin_instructions)
            setPadding(32, 24, 32, 24)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    checkForToken()
                }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        )

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        // Start from a clean slate so an old session can't hand over the wrong account.
        clearGoogleData {
            webView.loadUrl(EMBEDDED_SETUP_URL)
            handler.postDelayed(pollForToken, 1000)
        }
    }

    private fun checkForToken() {
        if (sent) return
        val token = CookieManager.getInstance().getCookie(GOOGLE_ACCOUNTS_URL)
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$TOKEN_COOKIE=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
            ?: return
        sent = true
        handler.removeCallbacks(pollForToken)
        status.setText(R.string.play_signin_sending)
        webView.stopLoading()
        sendToServer(token)
    }

    private fun sendToServer(token: String) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceToken = mdm.deviceToken()
        CoroutineScope(Dispatchers.IO).launch {
            val (ok, message) = if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
                false to getString(R.string.play_signin_not_enrolled)
            } else {
                try {
                    val response = createMdmApi(serverUrl, deviceToken).playSignIn(PlaySignInRequest(token))
                    val body = response.body()
                    when {
                        body != null -> body.ok to body.message
                        response.code() == 404 -> false to getString(R.string.play_signin_server_too_old)
                        else -> false to getString(R.string.play_signin_failed, "HTTP ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Sending the sign-in code failed", e)
                    false to getString(R.string.play_signin_failed, e.message ?: e.javaClass.simpleName)
                }
            }
            withContext(Dispatchers.Main) {
                clearGoogleData {}
                Toast.makeText(this@PlaySignInActivity, message, Toast.LENGTH_LONG).show()
                if (ok) {
                    finish()
                } else {
                    status.text = message
                    webView.loadUrl("about:blank")
                }
            }
        }
    }

    /** Removes every cookie and all web storage this app's WebViews hold - only Google's are ever
     * there, since this is the app's only WebView. */
    private fun clearGoogleData(then: () -> Unit) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            then()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollForToken)
        clearGoogleData {}
        webView.destroy()
        super.onDestroy()
    }
}
