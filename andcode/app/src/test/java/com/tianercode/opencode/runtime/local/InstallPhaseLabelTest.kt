package com.tianercode.opencode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test











class InstallPhaseLabelTest {
    private val tools = { count: Int -> if (count > 0) "正在安装运行所需工具  ·  $count 个包" else "正在安装运行所需工具" }
    private val resolving = "正在解析软件包依赖"
    private val fetching = "正在获取软件包索引"

    private fun label(
        allowNetworkFallback: Boolean,
        packages: Int,
        indexes: Int,
    ) = installPhaseLabel(
        allowNetworkFallback = allowNetworkFallback,
        packages = packages,
        indexes = indexes,
        toolsLabelFor = tools,
        resolvingLabel = resolving,
        fetchingLabel = fetching,
    )

    @Test
    fun `the offline path never mentions fetching or resolving`() {
        
        val observed =
            listOf(
                label(allowNetworkFallback = false, packages = 0, indexes = 0),
                label(allowNetworkFallback = false, packages = 0, indexes = 3),
                label(allowNetworkFallback = false, packages = 14, indexes = 3),
            )
        observed.forEach { text ->
            assertFalse("首装路径不能出现「获取」：$text", text.contains("获取"))
            assertFalse("首装路径不能出现「解析」：$text", text.contains("解析"))
            assertFalse("首装路径不能出现「下载」：$text", text.contains("下载"))
        }
    }

    @Test
    fun `the offline path keeps the tools label while the cache is still empty`() {
        
        assertEquals(
            "正在安装运行所需工具",
            label(allowNetworkFallback = false, packages = 0, indexes = 0),
        )
        
        assertEquals(
            "正在安装运行所需工具",
            label(allowNetworkFallback = false, packages = 0, indexes = 2),
        )
    }

    @Test
    fun `the offline path adds the package count once packages land`() {
        assertEquals(
            "正在安装运行所需工具  ·  7 个包",
            label(allowNetworkFallback = false, packages = 7, indexes = 2),
        )
    }

    @Test
    fun `the network path still names its two phases`() {
        
        assertEquals(
            fetching,
            label(allowNetworkFallback = true, packages = 0, indexes = 0),
        )
        assertEquals(
            resolving,
            label(allowNetworkFallback = true, packages = 0, indexes = 2),
        )
        assertEquals(
            "正在安装运行所需工具  ·  110 个包",
            label(allowNetworkFallback = true, packages = 110, indexes = 3),
        )
    }
}
