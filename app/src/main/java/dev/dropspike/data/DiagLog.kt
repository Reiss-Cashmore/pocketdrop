package dev.dropspike.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app diagnostic log. Never pass tokens to it: the log is meant to be copied and shared. */
object DiagLog {
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(msg: String) {
        Log.i("DropSpike", msg)
        val line = "${synchronized(fmt) { fmt.format(Date()) }}  $msg"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() = _lines.update { emptyList() }
}
