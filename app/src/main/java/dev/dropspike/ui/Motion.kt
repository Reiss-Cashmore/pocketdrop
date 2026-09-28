package dev.dropspike.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Motion vocabulary shared by every screen: one place to keep timings consistent. */
internal object MotionTokens {
    const val SHORT = 180
    const val MEDIUM = 320
    const val LONG = 520
    const val STAGGER = 70
}

/** Light haptics: [tick] for toggles and selections, [confirm] for starting or stopping mining. */
internal class Haptics(private val view: android.view.View) {
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

@Composable
internal fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** A status dot that breathes while something is live. */
@Composable
internal fun LiveDot(color: Color, size: Dp = 8.dp, pulsing: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "live")
    val halo by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "halo",
    )
    Box(Modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Canvas(Modifier.size(size * 2.2f)) {
                drawCircle(color.copy(alpha = 0.45f * (1f - halo)), radius = this.size.minDimension / 2 * (0.45f + 0.55f * halo))
            }
        }
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

/** Expanding rings: "looking for something". Drawn behind [content]. */
@Composable
internal fun RadarPulse(color: Color, size: Dp, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "phase",
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            repeat(3) { i ->
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    color.copy(alpha = 0.5f * (1f - p)),
                    radius = this.size.minDimension / 2 * (0.35f + 0.65f * p),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        content()
    }
}

/** Shrinks slightly while pressed; pass the same [interaction] to the clickable. */
@Composable
internal fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A number that counts to its new value instead of jumping. */
@Composable
internal fun AnimatedCount(value: Int, style: TextStyle, color: Color = Color.Unspecified, suffix: String = "") {
    val shown by animateIntAsState(value, tween(MotionTokens.LONG, easing = FastOutSlowInEasing), label = "count")
    Text("$shown$suffix", style = style, color = color)
}

/** Fades and lifts content in once, [index] steps after the first; for first-run screens. */
@Composable
internal fun Modifier.enterStagger(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(MotionTokens.LONG, delayMillis = index * MotionTokens.STAGGER, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}
