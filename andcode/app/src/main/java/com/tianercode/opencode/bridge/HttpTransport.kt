package com.tianercode.opencode.bridge

import com.tianercode.opencode.LocalUiServer


















class HttpTransport(
    private val server: LocalUiServer,
) : BridgeTransport {
    override fun resolve(
        ticket: String,
        ok: Boolean,
        payloadJson: String,
    ) {
        server.pushResolve(ticket, ok, payloadJson)
    }

    override fun emit(
        channel: String,
        payloadJson: String,
    ) {
        server.pushEvent(channel, payloadJson)
    }
}
