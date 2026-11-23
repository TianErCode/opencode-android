package com.tianercode.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test















class DesktopWebAssetsTest {
    private val mimeType: (String) -> String by lazy {
        val method = DesktopWebAssets::class.java.getDeclaredMethod("mimeType", String::class.java)
        method.isAccessible = true
        { asset -> method.invoke(DesktopWebAssets, asset) as String }
    }

    @Test
    fun `the asset root matches the staging layout`() {
        assertEquals("desktop-web/out/renderer", DesktopWebAssets.ROOT)
    }

    @Test
    fun `open refuses every traversal shape before touching assets`() {
        val context = UnusableContext()
        val rejected =
            listOf(
                "/../secret",
                "..",
                "a/../b",
                "/a/..",
                "..\\secret",
                "a\\b",
                "",
                "/",
            )
        rejected.forEach { path ->
            assertNull("path must be refused: '$path'", DesktopWebAssets.open(context, path))
        }
    }

    @Test
    fun `open returns null for a path that is not in the renderer tree`() {
        
        
        val context = UnusableContext()
        assertNull(DesktopWebAssets.open(context, "/assets/app.js"))
        assertNull(DesktopWebAssets.open(context, "/api/info"))
    }


    @Test
    fun `mime types match what the renderer ships`() {
        val expected =
            mapOf(
                "index.html" to "text/html",
                "chunk.htm" to "text/html",
                "app.js" to "text/javascript",
                "mod.mjs" to "text/javascript",
                "style.css" to "text/css",
                "data.json" to "application/json",
                "app.webmanifest" to "application/json",
                "app.js.map" to "application/json",
                "logo.svg" to "image/svg+xml",
                "icon.png" to "image/png",
                "photo.jpg" to "image/jpeg",
                "photo.jpeg" to "image/jpeg",
                "anim.gif" to "image/gif",
                "favicon.ico" to "image/x-icon",
                "logo.webp" to "image/webp",
                "font.woff2" to "font/woff2",
                "font.woff" to "font/woff",
                "font.ttf" to "font/ttf",
                "font.otf" to "font/otf",
                "module.wasm" to "application/wasm",
                "notes.txt" to "text/plain",
            )
        expected.forEach { (asset, mime) ->
            assertEquals("wrong mime for $asset", mime, mimeType(asset))
        }
    }

    @Test
    fun `unknown and missing extensions fall back to octet stream`() {
        assertEquals("application/octet-stream", mimeType("archive.bin"))
        assertEquals("application/octet-stream", mimeType("no-extension"))
        assertEquals("application/octet-stream", mimeType("trailing-dot."))
    }

    @Test
    fun `the extension check is case sensitive - uppercase JS is not javascript (finding)`() {
        
        
        assertEquals("application/octet-stream", mimeType("CHUNK.JS"))
        assertFalse(mimeType("CHUNK.JS").contains("javascript"))
    }
}
