package com.example.viewcast.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** Adresses IPv4 locales utilisables par le PC, la plus probable en premier (Wi-Fi / hotspot / Wi-Fi Direct). */
    fun localIpv4Addresses(): List<String> {
        val found = ArrayList<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("dummy") || name.startsWith("v4-") || name.startsWith("clat")
                ) continue
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 0
                    name.startsWith("p2p") -> 1
                    name.startsWith("eth") || name.startsWith("rndis") -> 2
                    else -> 3
                }
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { found.add(priority to it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun bestLocalIp(): String? = localIpv4Addresses().firstOrNull()
}
