package com.tianercode.opencode.runtime.local

import android.content.Context











internal object DeviceDns {

    fun servers(context: Context): List<String> =
        runCatching {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    ?: return emptyList()
            val network = manager.activeNetwork ?: return emptyList()
            val properties = manager.getLinkProperties(network) ?: return emptyList()
            val servers =
                properties.dnsServers
                    .mapNotNull { address -> address.hostAddress }
                    
                    .map { it.substringBefore('%') }
                    .filter { it.isNotBlank() }
            
            
            servers.distinct().take(2)
        }.getOrDefault(emptyList())
}
