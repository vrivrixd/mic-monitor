package com.vrivrixd.micmonitor

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** Descoberta do endereco que o computador deve digitar no navegador. */
object NetUtils {

    private const val TAG = "NetUtils"

    /**
     * Endereco IPv4 do aparelho na rede local, ou nulo quando nao ha rede util.
     * Interfaces de Wi-Fi e de roteador portatil tem preferencia.
     */
    fun localIpv4(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("eth") -> 2
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 9
                    else -> 5
                }
                for (address in iface.inetAddresses) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val text = address.hostAddress ?: continue
                    val bonus = if (address.isSiteLocalAddress) 0 else 4
                    candidates += (priority + bonus) to text
                }
            }
        } catch (e: SocketException) {
            Log.w(TAG, "Falha ao listar interfaces de rede", e)
            return null
        }
        return candidates.minByOrNull { it.first }?.second
    }

    fun addressFor(port: Int): String? {
        val ip = localIpv4() ?: return null
        return "http://" + ip + ":" + port
    }
}
