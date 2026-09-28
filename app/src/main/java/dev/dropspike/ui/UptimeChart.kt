package dev.dropspike.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dropspike.data.MinuteEntry
import dev.dropspike.data.MinuteStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Minute states as drawn. [Missed] is derived: a gap inside a session where no tick ran. */
private enum class Slot(val label: String, val color: Color, val height: Float) {
    // Fixed status palette (good / warning / critical), never reused for anything else.
    // Height is a second channel so the chart reads without relying on red vs green.
    Credited("Credited", Color(0xFF0CA30C), 1f),
    Sent("Sent, not yet credited", Color(0xFFFAB219), 0.66f),
    Failed("Failed", Color(0xFFD03B3B), 0.4f),
    Missed("Missed (not running)", Color(0xFFD03B3B), 0.4f),
    Idle("Idle, nothing to mine", Color(0xFF8A8A8A), 0.15f),
}

private enum class Window(val label: String, val minutes: Int, val bucketMinutes: Int) {
    Hour("1 h", 60, 1),
    SixHours("6 h", 360, 5),
    Day("24 h", 1440, 15),
}

private data class Bucket(val startMs: Long, val slot: Slot?, val counts: Map<Slot, Int>, val lastNote: String?)

private const val MINUTE = 60_000L

/**
 * Uptime bar for the mining session: one bar per minute (or per 5/15 minutes on longer
 * windows), coloured and sized by what that minute achieved. Tap a bar for details.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UptimeChart(entries: List<MinuteEntry>, running: Boolean) {
    var window by remember { mutableStateOf(Window.Hour) }
    var selected by remember { mutableStateOf<Int?>(null) }
    // Re-evaluate "now" every 30s so missed minutes appear even when no ticks arrive.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val buckets = remember(entries, window, now, running) { buildBuckets(entries, window, now, running) }
    val totals = remember(buckets) {
        Slot.entries.associateWith { s -> buckets.sumOf { it.counts[s] ?: 0 } }
    }
    val tracked = totals.values.sum()
    val credited = totals[Slot.Credited] ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tracked == 0) "No mining yet in this window" else "$credited of $tracked minutes credited (${credited * 100 / tracked}%)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Window.entries.forEach { w ->
                FilterChip(selected = window == w, onClick = { window = w; selected = null }, label = { Text(w.label) })
            }
        }

        val baseline = MaterialTheme.colorScheme.outlineVariant
        val highlight = MaterialTheme.colorScheme.onSurface
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "Mining uptime: $credited of $tracked minutes credited" }
                .pointerInput(buckets) {
                    detectTapGestures { pos ->
                        val i = (pos.x / size.width * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                        selected = if (selected == i) null else i
                    }
                },
        ) {
            val gap = 2.dp.toPx()
            val barW = ((size.width - gap * (buckets.size - 1)) / buckets.size).coerceAtLeast(1f)
            val radius = CornerRadius(minOf(2.dp.toPx(), barW / 2))
            drawLine(baseline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
            buckets.forEachIndexed { i, b ->
                val slot = b.slot ?: return@forEachIndexed
                val h = (size.height - 2.dp.toPx()) * slot.height
                val x = i * (barW + gap)
                drawRoundRect(slot.color, Offset(x, size.height - h), Size(barW, h), radius)
                if (i == selected) {
                    drawRoundRect(highlight, Offset(x - 1f, 0f), Size(barW + 2f, size.height), radius, alpha = 0.12f)
                }
            }
        }
        Row {
            Text(formatClock(buckets.firstOrNull()?.startMs ?: now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        selected?.let { buckets.getOrNull(it) }?.let { b ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(10.dp).fillMaxWidth()) {
                    val end = b.startMs + window.bucketMinutes * MINUTE
                    Text("${formatClock(b.startMs)}–${formatClock(end)} · ${b.slot?.label ?: "Not running"}", style = MaterialTheme.typography.labelLarge)
                    if (window.bucketMinutes > 1 && b.counts.isNotEmpty()) {
                        Text(b.counts.entries.joinToString(" · ") { "${it.key.label} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                    }
                    b.lastNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        // Legend: always present, labels in text colour, counts for the window.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Slot.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(width = 8.dp, height = (4 + 8 * s.height).dp)) {
                        drawRoundRect(s.color, cornerRadius = CornerRadius(2.dp.toPx()))
                    }
                    Text(" ${s.label} ${totals[s] ?: 0}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun buildBuckets(entries: List<MinuteEntry>, window: Window, now: Long, running: Boolean): List<Bucket> {
    val start = (now / MINUTE - window.minutes + 1) * MINUTE
    val slots = arrayOfNulls<Slot>(window.minutes)
    val notes = arrayOfNulls<String>(window.minutes)
    val sorted = entries.filter { it.atMs >= start - 3 * 60 * MINUTE }.sortedBy { it.atMs }

    fun index(t: Long) = ((t - start) / MINUTE).toInt()

    for (e in sorted) {
        val i = index(e.atMs)
        if (i in slots.indices) {
            slots[i] = when (e.status) {
                MinuteStatus.Credited -> Slot.Credited
                MinuteStatus.Sent -> Slot.Sent
                MinuteStatus.Failed -> Slot.Failed
                MinuteStatus.Idle -> Slot.Idle
            }
            notes[i] = e.note
        }
    }
    // Missed minutes: gaps of 1.5 min to 3 h between ticks (a session that stalled, not one that
    // was stopped), and the time since the last tick if the service claims to be running.
    fun markMissed(from: Long, to: Long) {
        var t = from + MINUTE
        while (t < to) {
            val i = index(t)
            if (i in slots.indices && slots[i] == null) slots[i] = Slot.Missed
            t += MINUTE
        }
    }
    sorted.zipWithNext().forEach { (a, b) ->
        val gap = b.atMs - a.atMs
        if (gap > 90_000 && gap < 3 * 60 * MINUTE) markMissed(a.atMs, b.atMs)
    }
    sorted.lastOrNull()?.let { if (running && now - it.atMs > 90_000) markMissed(it.atMs, now) }

    return (0 until window.minutes step window.bucketMinutes).map { b0 ->
        val range = b0 until minOf(b0 + window.bucketMinutes, window.minutes)
        val counts = range.mapNotNull { slots[it] }.groupingBy { it }.eachCount()
        // A bucket shows its most common state; ties go to the worse one (enum order is best → worst,
        // except Idle, which only wins when nothing else happened).
        val slot = counts.filterKeys { it != Slot.Idle }.maxWithOrNull(compareBy<Map.Entry<Slot, Int>> { it.value }.thenBy { it.key.ordinal })?.key
            ?: counts.keys.firstOrNull()
        Bucket(start + b0 * MINUTE, slot, counts, range.reversed().firstNotNullOfOrNull { notes[it] })
    }
}

private fun formatClock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
