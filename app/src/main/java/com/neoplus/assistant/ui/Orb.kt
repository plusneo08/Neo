package com.neoplus.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/** The NEO PLUS orb: breathes when idle, swells with the microphone level, orbits while thinking, pulses while speaking. */
@Composable
fun Orb(state: OrbState, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val lvl by animateFloatAsState(level, tween(90), label = "level")
    val tau = (2 * PI).toFloat()

    Canvas(modifier.size(240.dp)) {
        val c = center
        val base = size.minDimension / 4.2f
        val amp = when (state) {
            OrbState.LISTENING -> 0.05f + lvl * 0.35f
            OrbState.SPEAKING -> 0.10f + 0.08f * sin(phase * tau * 3)
            OrbState.THINKING -> 0.06f
            OrbState.IDLE -> 0.03f
        }
        val pulse = 1f + amp + 0.03f * sin(phase * tau)
        val tint = when (state) {
            OrbState.LISTENING -> Neo.Cyan
            OrbState.THINKING -> Neo.Purple
            OrbState.SPEAKING -> Neo.Blue
            OrbState.IDLE -> Neo.Cyan.copy(alpha = 0.75f)
        }
        for (k in 3 downTo 1) {
            drawCircle(tint.copy(alpha = 0.10f), radius = base * pulse * (1f + 0.28f * k), center = c)
        }
        val r = base * pulse
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), tint, Neo.Purple.copy(alpha = 0.55f), Color.Transparent),
                center = c, radius = r * 1.35f),
            radius = r * 1.35f, center = c,
        )
        val ring = base * 1.9f
        val spin = if (state == OrbState.THINKING) phase * 360f * 2 else phase * 360f * 0.25f
        rotate(spin, pivot = c) {
            drawArc(tint, 0f, 80f, false, topLeft = Offset(c.x - ring, c.y - ring), size = Size(ring * 2, ring * 2),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Neo.Purple.copy(alpha = 0.8f), 180f, 50f, false, topLeft = Offset(c.x - ring, c.y - ring), size = Size(ring * 2, ring * 2),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
