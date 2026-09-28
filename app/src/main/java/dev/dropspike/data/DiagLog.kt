package dev.dropspike.data

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log, persisted to disk so it survives the process being killed (which is exactly
 * when it matters: overnight runs). Rotates at [MAX_BYTES], keeping one previous file: several
 * days at roughly one line per network call and a few per mining minute.
 *
 * Never pass tokens to it: the log is meant to be shared.
 */
object DiagLog {
    private const val MAX_LINES_IN_MEMORY = 400
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var file: File? = null
    private var previous: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "diag.log")
        previous = File(dir, "diag.1.log")
        // Show the tail of what earlier processes wrote, so the UI isn't empty after a restart.
        _lines.value = runCatching { file!!.readLines().takeLast(MAX_LINES_IN_MEMORY) }.getOrDefault(emptyList())
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, t: Throwable? = null) =
        write("E", if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    @Synchronized
    private fun write(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level [${Thread.currentThread().name.take(18)}|${Process.myPid()}] $msg"
        when (level) {
            "E" -> Log.e("DropSpike", msg)
            "W" -> Log.w("DropSpike", msg)
            else -> Log.i("DropSpike", msg)
        }
        _lines.update { (it + line).takeLast(MAX_LINES_IN_MEMORY) }
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                previous?.delete()
                f.renameTo(previous!!)
            }
            f.appendText(line + "\n")
        }
    }

    /** Everything on disk, oldest first. */
    @Synchronized
    fun readAll(): List<String> = listOfNotNull(previous, file)
        .filter { it.exists() }
        .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }

    @Synchronized
    fun clear() {
        _lines.update { emptyList() }
        runCatching { file?.writeText(""); previous?.delete() }
    }
}
