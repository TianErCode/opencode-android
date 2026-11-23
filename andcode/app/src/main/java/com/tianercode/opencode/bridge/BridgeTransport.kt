package com.tianercode.opencode.bridge















interface BridgeTransport {
    
    fun resolve(
        ticket: String,
        ok: Boolean,
        payloadJson: String,
    )

    
    fun emit(
        channel: String,
        payloadJson: String,
    )
}
