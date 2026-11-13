package com.noveria.cast.tv

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

object Protocol {
    const val SERVICE_TYPE = "_noveriacast._tcp."
    const val CONTROL_PORT = 53535
    const val DISCOVERY_PORT = 53536
    const val DISCOVER_MSG = "NOVERIACAST_DISCOVER"
    const val REPLY_PREFIX = "NOVERIACAST_HERE"
    const val MSG_CONFIG = 1
    const val MSG_FRAME = 2
    const val MSG_STOP = 3
    const val MSG_PING = 4
}

/**
 * TV tarafı: NSD ile kendini duyurur, UDP keşfine cevap verir, TCP'den gelen H.264
 * akışını MediaCodec ile Surface'e çizer.
 */
class ReceiverServer(context: Context, private val deviceName: String) {

    interface Listener {
        fun onVideoSize(width: Int, height: Int)
        fun onSessionEnded()
    }

    @Volatile var listener: Listener? = null

    private class StreamConfig(val width: Int, val height: Int, val csd: ByteArray)

    private val appContext = context.applicationContext
    private val lock = Any()
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var config: StreamConfig? = null
    private var currentSocket: Socket? = null

    @Volatile private var running = false
    private var tcpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var regListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "NoveriaX-TCP") { runTcp() }
        thread(isDaemon = true, name = "NoveriaX-UDP") { runUdp() }
        registerNsd()
    }

    fun stop() {
        running = false
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try {
            regListener?.let {
                (appContext.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it)
            }
        } catch (_: Exception) {}
        regListener = null
    }

    /** Activity'deki SurfaceView hazır olunca / yok olunca çağrılır. */
    fun setSurface(s: Surface?) {
        synchronized(lock) {
            surface = s
            releaseDecoder()
            if (s != null && config != null) createDecoder()
        }
    }

    // ---------------- keşif ----------------

    private fun registerNsd() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = Protocol.SERVICE_TYPE
                port = Protocol.CONTROL_PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            }
            regListener = l
            (appContext.getSystemService(Context.NSD_SERVICE) as NsdManager)
                .registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
        }
    }

    private fun runUdp() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            udpSocket = s
            val buf = ByteArray(512)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = String(p.data, 0, p.length)
                if (text.startsWith(Protocol.DISCOVER_MSG)) {
                    val reply = "${Protocol.REPLY_PREFIX}|${deviceName.replace("|", " ")}|${Protocol.CONTROL_PORT}"
                        .toByteArray()
                    s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- TCP ----------------

    private fun runTcp() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Protocol.CONTROL_PORT))
            tcpServer = ss
            while (running) {
                val c = ss.accept()
                thread(isDaemon = true, name = "NoveriaX-Client") { handleClient(c) }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleClient(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            loop@ while (true) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > 8_000_000) break
                val payload = ByteArray(len)
                input.readFully(payload)
                when (type) {
                    Protocol.MSG_PING -> {
                        sock.getOutputStream().apply { write(1); flush() }
                        break@loop
                    }
                    Protocol.MSG_CONFIG -> onConfig(sock, payload)
                    Protocol.MSG_FRAME -> onFrame(sock, payload)
                    Protocol.MSG_STOP -> break@loop
                }
            }
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
            endSession(sock)
        }
    }

    private fun onConfig(sock: Socket, payload: ByteArray) {
        if (payload.size < 8) return
        val bb = ByteBuffer.wrap(payload)
        val w = bb.int
        val h = bb.int
        val csd = payload.copyOfRange(8, payload.size)
        synchronized(lock) {
            val prev = currentSocket
            currentSocket = sock
            if (prev != null && prev !== sock) {
                try { prev.close() } catch (_: Exception) {}
            }
            config = StreamConfig(w, h, csd)
            releaseDecoder()
            createDecoder()
        }
        listener?.onVideoSize(w, h)
    }

    private fun onFrame(sock: Socket, payload: ByteArray) {
        if (payload.size <= 8) return
        synchronized(lock) {
            if (currentSocket !== sock) return
            val c = decoder ?: return
            try {
                val pts = ByteBuffer.wrap(payload, 0, 8).long
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx)!!
                    buf.clear()
                    buf.put(payload, 8, payload.size - 8)
                    c.queueInputBuffer(idx, 0, payload.size - 8, pts, 0)
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o >= 0) {
                        c.releaseOutputBuffer(o, true)
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        continue
                    } else {
                        break
                    }
                }
            } catch (_: Exception) {
                releaseDecoder()
            }
        }
    }

    private fun endSession(sock: Socket) {
        var ended = false
        synchronized(lock) {
            if (currentSocket === sock) {
                currentSocket = null
                config = null
                releaseDecoder()
                ended = true
            }
        }
        if (ended) listener?.onSessionEnded()
    }

    // ---------------- decoder (lock altında çağrılır) ----------------

    private fun createDecoder() {
        val s = surface ?: return
        val cfg = config ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(cfg.csd))
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, s, null, 0)
            c.start()
            decoder = c
        } catch (_: Exception) {
            decoder = null
        }
    }

    private fun releaseDecoder() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }
}
