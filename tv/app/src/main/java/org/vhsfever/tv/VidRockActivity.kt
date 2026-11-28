package org.vhsfever.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Plays the VidRock hosted player as a separate provider.
 *
 * Archive.org remains on the native Media3 player; VidRock owns its own embedded player and
 * source selection, so we deliberately do not try to extract its streams into ExoPlayer.
 */
class VidRockActivity : Activity() {
    private lateinit var webView: WebView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || !url.startsWith("https://vidrock.net/")) {
            finish()
            return
        }

        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Keep the provider's own navigation in this player. External links are not
                    // allowed to take the TV app out of its playback screen.
                    val target = request?.url ?: return true
                    if (request.isForMainFrame) return target.scheme != "https" || target.host != "vidrock.net"
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    fullscreenCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                    webView.visibility = View.GONE
                    view.requestFocus()
                }

                override fun onHideCustomView() {
                    hideFullscreen()
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
        webView.loadUrl(url)
        webView.requestFocus()
    }

    private fun hideFullscreen() {
        val view = fullscreenView ?: return
        root.removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webView.visibility = View.VISIBLE
        webView.requestFocus()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (fullscreenView != null) {
            hideFullscreen()
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "vidrock_url"

        fun intent(context: Context, imdbId: String?, tmdbId: Int?): Intent? {
            val id = imdbId?.takeIf { it.startsWith("tt") && it.length > 2 }
                ?: tmdbId?.takeIf { it > 0 }?.toString()
                ?: return null
            val url = "https://vidrock.net/movie/$id"
            return Intent(context, VidRockActivity::class.java).putExtra(EXTRA_URL, url)
        }
    }
}
