package vikify.vpn.core

import android.os.ParcelFileDescriptor
import android.util.Log
import engine.Engine
import engine.Key
import vikify.vpn.BuildConfig

private const val TAG = "Tun2socks"
private const val SOCKS_PROXY = "socks5://127.0.0.1:10808"

/**
 * Manages tun2socks: forwards TUN traffic to xray's SOCKS inbound (127.0.0.1:10808).
 * Uses xjasonlyu/tun2socks engine AAR (in-process).
 *
 * FDSAN (Android 12+): the Go engine closes the device fd when stopped. We must not pass
 * the fd owned by ParcelFileDescriptor (from VpnService.Builder.establish()) or FDSAN
 * will kill the process. So we dup the PFD, detachFd() to get an unowned fd, and pass
 * that to the engine. The original PFD is only closed once by the caller (VikifyVpnService).
 */
class Tun2socksManager {

    private var started = false

    /**
     * Starts tun2socks in-process with the given TUN fd. Passes a detached duplicate fd to the
     * Go engine so that when the engine stops and closes the fd, FDSAN is not violated.
     * If Go never starts (insert/start fails), fdForGo is closed from Java to avoid leak.
     * Returns true if started successfully.
     */
    fun start(tunnelFd: ParcelFileDescriptor): Boolean {
        if (started) return true
        val dupPfd = ParcelFileDescriptor.dup(tunnelFd.fileDescriptor)
        val fdForGo = dupPfd.detachFd()
        dupPfd.close()
        return runCatching {
            val key = Key().apply {
                setMark(0)
                setMTU(0)
                setDevice("fd://$fdForGo")
                setProxy(SOCKS_PROXY)
                setLogLevel("warn")
                setInterface("")
                setRestAPI("")
                setTCPSendBufferSize("")
                setTCPReceiveBufferSize("")
                setTCPModerateReceiveBuffer(false)
            }
            Engine.insert(key)
            Engine.start()
            started = true
            if (BuildConfig.DEBUG) Log.i(TAG, "tun2socks engine started (fd://$fdForGo)")
            true
        }.getOrElse { e ->
            if (BuildConfig.DEBUG) Log.e(TAG, "tun2socks start failed: ${e.message}")
            closeDetachedFd(fdForGo)
            false
        }
    }

    /** Closes a detached fd (no PFD owner). Use when start failed and Go never took ownership. */
    private fun closeDetachedFd(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "closeDetachedFd($fd) failed: ${t.message}")
        }
    }

    /** Stops the engine. Idempotent: safe to call multiple times. */
    fun stop() {
        if (!started) return
        try {
            runCatching {
                Engine.stop()
                if (BuildConfig.DEBUG) Log.i(TAG, "tun2socks engine stopped")
            }.onFailure { e ->
                if (BuildConfig.DEBUG) Log.e(TAG, "tun2socks stop error: ${e.message}")
            }
        } finally {
            started = false
        }
    }
}
