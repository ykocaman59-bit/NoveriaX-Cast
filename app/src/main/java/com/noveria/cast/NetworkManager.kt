package com.noveria.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.LinkedList
import kotlin.concurrent.thread

/** Telefon ile TV alıcısı arasındaki basit TCP protokolü. */
object Protocol {
    const val SERVICE_TYPE = "_noveriacast._tcp."
    const val CONTROL_PORT = 53535
    const val DISCOVERY_PORT = 53536
    const val DISCOVER_MSG = "NOVERIACAST_DISCOVER"
    const val REPLY_PREFIX = "NOVERIACAST_HERE"

    // Mesaj: [tip:1 bayt][uzunluk:4 bayt][veri]
    const val MSG_CONFIG = 1 // veri: [genişlik:int][yükseklik:int][SPS/PPS]
    const val MSG_FRAME = 2  // veri: [pts:long][H.264 verisi]
    const val MSG_STOP = 3   // yansıtma bitti, TV ana ekrana dönsün
    const val MSG_PING = 4   // çevrimiçi kontrolü, cevap: 1 bayt (0x01)
}

class NetworkManager(context: Context) {

    interface DiscoveryCallback {
        fun onDeviceFound(device: TvDevice)
        fun onFinished()
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var scanId = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedList<NsdServiceInfo>()
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null

    // ------------------------------------------------------------------
    // Wi-Fi durumu
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> = cm.allNetworks.filter {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Telefon bir Wi-Fi ağına bağlı mı? */
    fun isPhoneWifiConnected(): Boolean = wifiNetworks().isNotEmpty()

    /**
     * TV alıcısı ağ üzerinden cevap veriyor mu? (Bloklar, arka plan iş parçacığında çağırın.)
     * Cevap vermiyorsa TV kapalı ya da TV'nin Wi-Fi bağlantısı yok demektir.
     */
    fun isDeviceReachable(device: TvDevice, timeoutMs: Int = 1500): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(device.host, device.port), timeoutMs)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                out.write(byteArrayOf(Protocol.MSG_PING.toByte(), 0, 0, 0, 0))
                out.flush()
                s.getInputStream().read() == 1
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Cihaz keşfi (NSD + UDP broadcast)
    // ------------------------------------------------------------------

    fun startDiscovery(callback: DiscoveryCallback) {
        stopDiscovery()
        val myId = ++scanId
        val seen = HashSet<String>()

        val deliver: (TvDevice) -> Unit = { d ->
            mainHandler.post {
                if (myId == scanId && seen.add(d.id)) callback.onDeviceFound(d)
            }
        }

        try {
            multicastLock = wifi.createMulticastLock("noveriax_cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        startNsd(deliver)
        startUdp(myId, deliver)

        mainHandler.postDelayed({
            if (myId == scanId) {
                stopDiscovery()
                callback.onFinished()
            }
        }, SCAN_MS)
    }

    fun stopDiscovery() {
        scanId++
        mainHandler.removeCallbacksAndMessages(null)
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun startNsd(deliver: (TvDevice) -> Unit) {
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("noveriacast")) {
                    synchronized(resolveQueue) { resolveQueue.add(info) }
                    resolveNext(deliver)
                }
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discoveryListener = null
        }
    }

    /** NSD aynı anda tek çözümlemeye izin verir; sıraya alıyoruz. */
    @Suppress("DEPRECATION")
    private fun resolveNext(deliver: (TvDevice) -> Unit) {
        val info = synchronized(resolveQueue) {
            if (resolving) null else resolveQueue.poll()?.also { resolving = true }
        } ?: return
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(resolveQueue) { resolving = false }
                    resolveNext(deliver)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    if (host is Inet4Address && host.hostAddress != null) {
                        deliver(TvDevice(serviceInfo.serviceName, host.hostAddress!!, serviceInfo.port))
                    }
                    synchronized(resolveQueue) { resolving = false }
                    resolveNext(deliver)
                }
            })
        } catch (_: Exception) {
            synchronized(resolveQueue) { resolving = false }
        }
    }

    private fun startUdp(id: Int, deliver: (TvDevice) -> Unit) {
        thread(isDaemon = true, name = "NoveriaX-UDP-Scan") {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 400
                }
                val payload = Protocol.DISCOVER_MSG.toByteArray()
                val targets = broadcastAddresses()
                val end = SystemClock.elapsedRealtime() + SCAN_MS
                var lastSend = 0L
                val buf = ByteArray(512)
                while (id == scanId && SystemClock.elapsedRealtime() < end) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSend > 1000) {
                        targets.forEach {
                            try {
                                sock.send(DatagramPacket(payload, payload.size, it, Protocol.DISCOVERY_PORT))
                            } catch (_: Exception) {
                            }
                        }
                        lastSend = now
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val parts = String(p.data, 0, p.length).split("|")
                        if (parts.size >= 3 && parts[0] == Protocol.REPLY_PREFIX) {
                            val port = parts[2].toIntOrNull() ?: Protocol.CONTROL_PORT
                            val host = p.address.hostAddress
                            if (host != null) deliver(TvDevice(parts[1], host, port))
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                sock?.close()
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (net in wifiNetworks()) {
                val lp = cm.getLinkProperties(net) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address) {
                        val b = addr.address
                        val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
                        val prefix = la.prefixLength
                        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                        val bc = ip or mask.inv()
                        val bytes = byteArrayOf(
                            (bc ushr 24).toByte(), (bc ushr 16).toByte(),
                            (bc ushr 8).toByte(), bc.toByte()
                        )
                        result.add(InetAddress.getByAddress(bytes))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private companion object {
        const val SCAN_MS = 5000L
    }
}
