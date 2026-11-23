package com.tianercode.opencode

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import com.tianercode.opencode.runtime.local.LocalRuntimeCommandResult
import com.tianercode.opencode.ui.RescueCommandExecutor
import com.tianercode.opencode.ui.RescueTerminalView
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors



















class RescueTerminalController(
    private val activity: ComponentActivity,
    
    private val overlayHost: ViewGroup,
    
    private val app: OpenCodeApplication,
    
    private val engineOrigin: () -> String,
    
    private val onChromeChanged: () -> Unit,
) {
    
    private var terminal: RescueTerminalView? = null

    





    private val commandExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "rescue-terminal").apply { isDaemon = true }
        }

    
    val isOpen: Boolean
        get() = terminal?.visibility == View.VISIBLE

    
    fun open() {
        terminal?.let { existing ->
            existing.visibility = View.VISIBLE
            existing.bringToFront()
            
            onChromeChanged()
            return
        }
        val created =
            RescueTerminalView(
                context = activity,
                
                executor =
                    RescueCommandExecutor { command, onResult ->
                        commandExecutor.execute {
                            val result =
                                runCatching {
                                    app.localRuntimeCommandRunner.runShell(command, COMMAND_TIMEOUT_SECONDS)
                                }.getOrElse { error ->
                                    
                                    LocalRuntimeCommandResult(
                                        exitCode = -1,
                                        output = "宿主层错误：${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                onResult(result)
                            }
                        }
                    },
                onReadEngineLog = ::readEngineLog,
                onRestartEngine = ::restartRuntime,
                onClose = ::close,
            )
        overlayHost.addView(
            created,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        
        
        
        
        
        
        
        
        
        
        created.bringToFront()
        terminal = created
        onChromeChanged()
    }

    
    fun close() {
        val current = terminal ?: return
        if (current.visibility != View.VISIBLE) return
        current.visibility = View.GONE
        
        runCatching {
            val focused = activity.currentFocus ?: activity.window.decorView
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        activity.currentFocus?.clearFocus()
        
        onChromeChanged()
    }

    
    fun dispose() {
        commandExecutor.shutdown()
        terminal = null
    }

    

    






    private fun readEngineLog() {
        val current = terminal ?: return
        val logFile = File(File(activity.filesDir, "runtime"), "logs/opencode-local.log")
        current.appendHostOutput(
            "引擎日志",
            "宿主文件 ${logFile.absolutePath}（末尾 $HOST_LOG_CHARACTERS 字符，原样）",
        )
        commandExecutor.execute {
            val text =
                runCatching {
                    if (!logFile.isFile) {
                        "宿主层找不到日志文件：${logFile.absolutePath}"
                    } else {
                        logFile.readText().takeLast(HOST_LOG_CHARACTERS)
                    }
                }.getOrElse { error ->
                    "宿主层读取日志失败：${error.message ?: error.javaClass.simpleName}"
                }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                current.appendHostOutput("引擎日志", text)
            }
        }
    }

    






    private fun restartRuntime() {
        val current = terminal ?: return
        current.appendHostOutput("重启引擎", "已向本地运行时发送启动请求，结果稍后会追加在这里。")
        app.applicationScope.launch {
            val result = app.localRuntimeManager.start()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                current.appendHostOutput(
                    "重启引擎",
                    result.fold(
                        onSuccess = { status -> describeRuntimeStatus(status) },
                        onFailure = { error -> "启动失败：${error.message ?: error.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    
    private fun describeRuntimeStatus(status: LocalRuntimeStatus): String =
        when (status) {
            is LocalRuntimeStatus.Ready -> "运行环境已就绪：OpenCode ${status.version}，端口 ${status.port}"
            is LocalRuntimeStatus.Starting -> "运行环境正在启动：OpenCode ${status.version}，端口 ${status.port}"
            is LocalRuntimeStatus.Stopped -> "运行环境未运行：OpenCode ${status.version}，端口 ${status.port}"
            is LocalRuntimeStatus.Broken -> "运行环境出错：${status.reason}"
            is LocalRuntimeStatus.AwaitingStoragePermission -> "缺少「所有文件访问权限」，运行环境不能启动。"
            is LocalRuntimeStatus.NotInstalled -> "运行环境还没有安装。"
            is LocalRuntimeStatus.Installing -> "运行环境正在安装：${status.step}"
            is LocalRuntimeStatus.Updating -> "运行环境正在更新：${status.step}"
            is LocalRuntimeStatus.UnsupportedAbi -> "当前设备架构不受支持：${status.abi}"
        }

    private companion object {
        





        const val COMMAND_TIMEOUT_SECONDS = 30L

        






        const val HOST_LOG_CHARACTERS = 12_000
    }
}
