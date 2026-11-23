package com.tianercode.opencode

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import com.tianercode.opencode.ui.HostStyle
import com.tianercode.opencode.ui.OpenCodeLogoView




























class HostStatusView(
    context: Context,
    
    private val onRequestStorageAccess: () -> Unit,
    
    private val onOpenRescueTerminal: () -> Unit,
) : FrameLayout(context) {
    private val waitingLogo = OpenCodeLogoView(context)
    private val waitingTitle = TextView(context)
    private val waitingDetail = TextView(context)
    private val waitingRoot = LinearLayout(context)
    private val waitingDetailScroll = ScrollView(context)

    
    private val storageLogo = OpenCodeLogoView(context)
    private val storageTitle = TextView(context)
    private val storageDetail = TextView(context)
    private val storageActionButton = Button(context)
    private val storageRoot = LinearLayout(context)

    
    private val rescueEntryButton = Button(context)

    








    private val copyErrorButton = Button(context)

    
    private val actionRow = LinearLayout(context)

    
    private var lastStatus: LocalRuntimeStatus? = null

    





    private var pageErrorDetail: String? = null

    
    private var rescueEntryReveal: Runnable? = null

    private val hostHandler = Handler(Looper.getMainLooper())

    
    private val logoPulse =
        ObjectAnimator.ofFloat(waitingLogo, View.ALPHA, 1f, 0.5f).apply {
            duration = 2_000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

    private fun spacePx(resId: Int): Int = resources.getDimensionPixelSize(resId)

    





    private fun copyFailureToClipboard() {
        val text = waitingDetail.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenCode 错误信息", text))
        copyErrorButton.text = "已复制"
        hostHandler.postDelayed({ copyErrorButton.text = "复制错误信息" }, 1_500)
    }

    init {
        
        buildWaitingScreen()
        buildStoragePrompt()
        addView(
            waitingRoot,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            storageRoot,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        isClickable = true
        syncSelfVisibility()
    }

    















    private fun syncSelfVisibility() {
        val anyContent =
            waitingRoot.visibility == View.VISIBLE || storageRoot.visibility == View.VISIBLE
        
        this.visibility = if (anyContent) View.VISIBLE else View.GONE

    }

    

    
    fun showWaiting() {
        waitingRoot.visibility = View.VISIBLE
        syncSelfVisibility()
        startLogoPulse()
        
        
        scheduleRescueEntryReveal()
    }

    
    fun hideWaiting() {
        pageErrorDetail = null
        waitingRoot.visibility = View.GONE
        syncSelfVisibility()
        stopLogoPulse()
        cancelRescueEntryReveal()
        rescueEntryButton.visibility = View.GONE
        updateActionRowVisibility()
    }

    






    fun showPageError(detail: String) {
        pageErrorDetail = detail
        waitingTitle.text = "界面加载失败"
        waitingDetail.text = detail
        applyStatusColors()
        applyDetailLayout(broken = true)
        stopLogoPulse()
        revealRescueEntryNow()
        copyErrorButton.visibility = View.VISIBLE
        waitingRoot.visibility = View.VISIBLE
        syncSelfVisibility()
    }

    




    fun showStoragePrompt(visible: Boolean) {
        storageRoot.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            waitingRoot.visibility = View.GONE
            stopLogoPulse()
        }
        syncSelfVisibility()
    }

    





    fun render(status: LocalRuntimeStatus) {
        pageErrorDetail = null
        lastStatus = status
        val title: String
        val detail: String
        when (status) {
            is LocalRuntimeStatus.NotInstalled -> {
                title = "正在准备运行环境…"
                detail = "首次启动会久一点，请稍候。"
            }
            is LocalRuntimeStatus.AwaitingStoragePermission -> {
                title = "需要「所有文件访问权限」"
                detail = "授权后，OpenCode 才能打开手机上的项目文件夹。"
            }
            is LocalRuntimeStatus.Installing -> {
                val percent = status.progress?.let { " ${(it * 100).toInt()}%" } ?: ""
                title = "正在安装运行环境$percent"
                detail = status.step
            }
            is LocalRuntimeStatus.Updating -> {
                val percent = status.progress?.let { " ${(it * 100).toInt()}%" } ?: ""
                title = "正在更新运行环境$percent"
                detail = status.step
            }
            is LocalRuntimeStatus.Starting -> {
                title = "正在启动 OpenCode…"
                detail = "请稍候。"
            }
            is LocalRuntimeStatus.Ready -> {
                title = "正在载入界面…"
                detail = "首次载入会慢一点。"
            }
            is LocalRuntimeStatus.Stopped -> {
                title = "正在启动…"
                detail = "请稍候。"
            }
            is LocalRuntimeStatus.Broken -> {
                title = "运行环境出错"
                detail = status.reason
            }
            is LocalRuntimeStatus.UnsupportedAbi -> {
                title = "不支持当前设备架构"
                detail = status.abi
            }
        }
        waitingTitle.text = title
        waitingDetail.text = detail
        applyStatusColors()
        applyDetailLayout(broken = status is LocalRuntimeStatus.Broken)
        
        if (status is LocalRuntimeStatus.Broken) {
            revealRescueEntryNow()
            copyErrorButton.visibility = View.VISIBLE
        } else {
            copyErrorButton.visibility = View.GONE
        }
        updateActionRowVisibility()
    }

    








    fun applyPalette(themeBackground: Int) {
        waitingRoot.setBackgroundColor(themeBackground)
        storageRoot.setBackgroundColor(themeBackground)
        waitingLogo.refreshColors()
        storageLogo.refreshColors()
        applyStatusColors()
        HostStyle.applyButton(copyErrorButton, HostStyle.ButtonVariant.SECONDARY)
        HostStyle.applyButton(rescueEntryButton, HostStyle.ButtonVariant.SECONDARY)
        HostStyle.applyButton(storageActionButton, HostStyle.ButtonVariant.PRIMARY)
    }

    
    fun release() {
        cancelRescueEntryReveal()
        logoPulse.cancel()
    }

    

    private fun buildWaitingScreen() {
        waitingLogo.apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.oc_logo_width),
                    resources.getDimensionPixelSize(R.dimen.oc_logo_height),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = spacePx(R.dimen.oc_space_6)
                }
        }

        waitingTitle.apply {
            text = "正在启动…"
            HostStyle.applyTitle(this)
        }

        waitingDetail.apply {
            text = ""
            HostStyle.applyBody(this)
            
            setTextIsSelectable(true)
        }

        
        
        
        
        
        
        
        
        waitingDetailScroll.apply {
            isFillViewport = true
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(
                        waitingDetail,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        
        
        rescueEntryButton.apply {
            text = "打开救援终端"
            HostStyle.applyButton(this, HostStyle.ButtonVariant.SECONDARY)
            visibility = View.GONE
            setOnClickListener { onOpenRescueTerminal() }
        }
        copyErrorButton.apply {
            text = "复制错误信息"
            HostStyle.applyButton(this, HostStyle.ButtonVariant.SECONDARY)
            visibility = View.GONE
            setOnClickListener { copyFailureToClipboard() }
        }

        actionRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(
                copyErrorButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                rescueEntryButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = spacePx(R.dimen.oc_space_2) },
            )
        }

        waitingRoot.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            
            setPadding(spacePx(R.dimen.oc_space_8), spacePx(R.dimen.oc_space_6), spacePx(R.dimen.oc_space_8), spacePx(R.dimen.oc_space_6))
            setBackgroundColor(HostStyle.color(context, R.color.oc_background))
            visibility = View.GONE
            addView(waitingLogo)
            addView(
                waitingTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                waitingDetailScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = spacePx(R.dimen.oc_space_2) },
            )
            addView(
                actionRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = spacePx(R.dimen.oc_space_6)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
    }

    private fun buildStoragePrompt() {
        storageLogo.apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.oc_logo_width),
                    resources.getDimensionPixelSize(R.dimen.oc_logo_height),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = spacePx(R.dimen.oc_space_6)
                }
        }

        storageTitle.apply {
            text = "OpenCode 需要「所有文件访问权限」"
            HostStyle.applyTitle(this)
        }
        storageDetail.apply {
            text = "授权后，OpenCode 才能打开手机上的项目文件夹。" +
                "\n" +
                "之后在 OpenCode 里添加项目时再选具体目录。"
            HostStyle.applyBody(this)
        }
        storageActionButton.apply {
            text = "去授权"
            HostStyle.applyButton(this, HostStyle.ButtonVariant.PRIMARY)
            setOnClickListener { onRequestStorageAccess() }
        }

        storageRoot.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(spacePx(R.dimen.oc_space_8), spacePx(R.dimen.oc_space_6), spacePx(R.dimen.oc_space_8), spacePx(R.dimen.oc_space_6))
            setBackgroundColor(HostStyle.color(context, R.color.oc_background))
            visibility = View.GONE
            addView(storageLogo)
            addView(
                storageTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                storageDetail,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = spacePx(R.dimen.oc_space_2) },
            )
            addView(
                storageActionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = spacePx(R.dimen.oc_space_6)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
    }

    

    
    private fun applyStatusColors() {
        waitingTitle.setTextColor(HostStyle.color(context, R.color.oc_text_primary))
        waitingDetail.setTextColor(
            HostStyle.color(
                context,
                if (pageErrorDetail != null || lastStatus is LocalRuntimeStatus.Broken) {
                    R.color.oc_text_danger
                } else {
                    R.color.oc_text_secondary
                },
            ),
        )
        storageTitle.setTextColor(HostStyle.color(context, R.color.oc_text_primary))
        storageDetail.setTextColor(HostStyle.color(context, R.color.oc_text_secondary))
    }

    




    private fun applyDetailLayout(broken: Boolean) {
        waitingDetailScroll.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (broken) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = spacePx(R.dimen.oc_space_2)
                if (broken) weight = 1f
            }
    }

    private fun updateActionRowVisibility() {
        val anyVisible = copyErrorButton.visibility == View.VISIBLE || rescueEntryButton.visibility == View.VISIBLE
        actionRow.visibility = if (anyVisible) View.VISIBLE else View.GONE
    }

    

    private fun startLogoPulse() {
        if (!logoPulse.isStarted) logoPulse.start()
    }

    private fun stopLogoPulse() {
        logoPulse.cancel()
        waitingLogo.alpha = 1f
    }

    

    







    private fun scheduleRescueEntryReveal() {
        if (rescueEntryReveal != null) return
        val reveal =
            Runnable {
                rescueEntryReveal = null
                if (waitingRoot.visibility == View.VISIBLE) {
                    rescueEntryButton.visibility = View.VISIBLE
                    updateActionRowVisibility()
                }
            }
        rescueEntryReveal = reveal
        hostHandler.postDelayed(reveal, RESCUE_ENTRY_DELAY_MILLIS)
    }

    private fun cancelRescueEntryReveal() {
        rescueEntryReveal?.let { hostHandler.removeCallbacks(it) }
        rescueEntryReveal = null
    }

    





    private fun revealRescueEntryNow() {
        cancelRescueEntryReveal()
        rescueEntryButton.visibility = View.VISIBLE
        updateActionRowVisibility()
    }

    private companion object {
        






        const val RESCUE_ENTRY_DELAY_MILLIS = 20_000L
    }
}
