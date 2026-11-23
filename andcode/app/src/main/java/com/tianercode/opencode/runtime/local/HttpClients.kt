package com.tianercode.opencode.runtime.local

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException








































internal object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved =
            try {
                Dns.SYSTEM.lookup(hostname)
            } catch (error: UnknownHostException) {
                throw error
            }
        if (resolved.size < 2) return resolved
        
        
        return resolved.sortedBy { address ->
            when (address) {
                is Inet4Address -> 0
                is Inet6Address -> 1
                else -> 2
            }
        }
    }
}





internal fun downloadHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .build()


internal fun OkHttpClient.Builder.ipv4First(): OkHttpClient.Builder = dns(Ipv4FirstDns)














internal fun streamingHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
