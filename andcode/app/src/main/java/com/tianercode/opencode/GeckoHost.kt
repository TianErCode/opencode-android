package com.tianercode.opencode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.atomic.AtomicReference
















object GeckoHost {
    








    private val runtimeHolder = AtomicReference<GeckoRuntime?>(null)

    
    fun sharedRuntime(context: Context): GeckoRuntime {
        runtimeHolder.get()?.let { return it }
        synchronized(runtimeHolder) {
            runtimeHolder.get()?.let { return it }
            val settings =
                GeckoRuntimeSettings.Builder()
                    
                    
                    
                    .consoleOutput(BuildConfig.DEBUG)
                    
                    
                    .doubleTapZoomingEnabled(false)
                    .inputAutoZoomEnabled(false)
                    .build()
            return GeckoRuntime.create(context.applicationContext, settings)
                .also { runtimeHolder.set(it) }
        }
    }

    





    fun newSession(): GeckoSession =
        GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .useTrackingProtection(false)
                
                
                
                
                
                
                
                
                
                
                
                
                .userAgentOverride(DESKTOP_LINUX_USER_AGENT)
                .build(),
        )

    







    fun createView(
        activity: Activity,
        container: ViewGroup,
        session: GeckoSession,
    ): GeckoView =
        GeckoView(activity).also {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.setSession(session)
            it.setBackgroundColor(ContextCompat.getColor(activity, R.color.oc_background))
            container.addView(
                it,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            it.requestFocus()
        }

    











    fun configure(
        activity: Activity,
        session: GeckoSession,
        onCanGoBack: (Boolean) -> Unit,
        onLocation: (String) -> Unit,
        onFirstContentfulPaint: () -> Unit,
        onTitle: (String?) -> Unit,
    ) {
        session.navigationDelegate =
            object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny>? {
                    val host = request.uri.substringAfter("://").substringBefore('/').substringBefore(':')
                    return if (host == "127.0.0.1" || host == "localhost") {
                        GeckoResult.allow()
                    } else {
                        
                        
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.uri))) }
                        GeckoResult.deny()
                    }
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    onCanGoBack(canGoBack)
                }

                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean,
                ) {
                    onLocation(url.orEmpty())
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {}
            }
        session.contentDelegate =
            object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) { android.util.Log.e("MainActivity", "renderer crashed") }

                override fun onKill(session: GeckoSession) { android.util.Log.e("MainActivity", "renderer killed") }

                






                override fun onFirstContentfulPaint(session: GeckoSession) {
                    onFirstContentfulPaint()
                }

                override fun onTitleChange(session: GeckoSession, title: String?) {
                    onTitle(title)
                }
            }
        session.progressDelegate =
            object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {}

                override fun onPageStop(session: GeckoSession, success: Boolean) {}

                override fun onProgressChange(session: GeckoSession, progress: Int) {}

                override fun onSecurityChange(
                    session: GeckoSession,
                    securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
                ) {}
            }
    }

    







    const val DESKTOP_LINUX_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:150.0) Gecko/20100101 Firefox/150.0"
}
