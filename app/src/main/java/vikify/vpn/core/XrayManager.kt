package vikify.vpn.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import vikify.vpn.BuildConfig

class XrayManager(private val context: Context) {
    @Volatile private var process: Process? = null
    private var drainJob: Job? = null
    val isAlive: Boolean get() = process?.isAlive == true

    /** Called on IO. Validation output can contain credentials and is discarded. */
    fun start(configJson: String) {
        check(process == null)
        val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.isFile)
        val emulatorRoot = if (BuildConfig.EMULATOR_TESTING &&
            context.assets.list("")?.contains("emulator-root.pem") == true) {
            File(context.filesDir, "emulator-root.pem").apply {
                context.assets.open("emulator-root.pem").use { input -> outputStream().use { input.copyTo(it) } }
            }
        } else null
        fun command(vararg args: String) = ProcessBuilder(binary.path, *args).apply {
            emulatorRoot?.let { environment()["SSL_CERT_FILE"] = it.path }
        }
        val validation = command("run", "-test", "-format", "json", "-config", "stdin:")
            .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        try {
            validation.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(configJson) }
            check(validation.waitFor(10, TimeUnit.SECONDS) && validation.exitValue() == 0)
        } finally {
            if (validation.isAlive) validation.destroyForcibly()
        }
        val started = command("run", "-format", "json", "-config", "stdin:")
            .directory(context.filesDir).redirectErrorStream(true).start()
        process = started
        started.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(configJson) }
        drainJob = CoroutineScope(Dispatchers.IO).launch {
            runCatching { started.inputStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) { /* discard configuration-dependent diagnostics */ }
            } }
        }
        // Process.start() does not mean the SOCKS listener is ready, especially on
        // slow devices. Wait for the actual listener instead of a fixed UI delay.
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            check(started.isAlive) { "Proxy core exited before opening its listener" }
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 10808), 200)
                }
                return
            } catch (_: java.io.IOException) {
                Thread.sleep(100)
            }
        }
        error("Proxy listener did not start")
    }

    /** Wait for native processes to release the SOCKS port before starting a new session. */
    fun stop() {
        val old = process
        process = null
        if (old != null) {
            old.destroy()
            if (!old.waitFor(2, TimeUnit.SECONDS)) {
                old.destroyForcibly()
                old.waitFor(2, TimeUnit.SECONDS)
            }
            runCatching { old.inputStream.close() }
        }
        drainJob?.cancel()
        drainJob = null
    }
}
