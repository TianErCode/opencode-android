package com.tianercode.opencode

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
























object DesktopWebAssets {
    
    const val ROOT = "desktop-web/out/renderer"


    





    fun open(context: Context, path: String): WebResourceResponse? {
        val clean = path.trimStart('/')
        
        if (clean.isEmpty() || clean.contains("..") || clean.contains('\\')) return null
        val asset = "$ROOT/$clean"
        val bytes = read(context, asset) ?: return null
        return response(asset, bytes)
    }

    private fun read(context: Context, asset: String): ByteArray? =
        runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()

    private fun response(asset: String, bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(mimeType(asset), "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(bytes))

    







    private fun mimeType(asset: String): String =
        when (asset.substringAfterLast('.', "")) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "webmanifest" -> "application/json"
            "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
}
