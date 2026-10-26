package com.headunit.music.phone.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import org.jetbrains.compose.resources.painterResource
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// The little animations the Now Playing tiles play when switched on (see GlassTile).
/**
 * The microphone with music notes floating out of its head, for the lyrics
 * tile's animation: three notes, one after another, drifting up and out with a
 * little sway, fading as they go. The mic stays in its regular weight.
 */
@Composable
internal fun MicNotesGlyph(t: Float) {
    Box(Modifier.fillMaxSize()) {
        Icon(
            painterResource(Res.drawable.ic_lyrics),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val k = size.minDimension / 24f // the icon's 24x24 grid
            // The mic's head (top-right of the tilted icon).
            val headX = 16.2f
            val headY = 7.8f
            // Each note: when it leaves (0..1 of the animation), which way it heads (degrees,
            // 0 = right, -90 = up), and how far it goes (grid units).
            val notes = listOf(Triple(0.00f, -35f, 15f), Triple(0.17f, -70f, 13f), Triple(0.34f, -10f, 16f))
            notes.forEachIndexed { i, (start, deg, dist) ->
                val u = ((t - start) / 0.62f).coerceIn(0f, 1f)
                if (u <= 0f || u >= 1f) return@forEachIndexed
                val a = (deg * PI / 180.0).toFloat()
                val ease = 1f - (1f - u) * (1f - u)
                val sway = sin(u * PI.toFloat() * 2.2f + i) * 1.6f
                // Sway sideways to the direction of travel.
                val x = headX + cos(a) * dist * ease - sin(a) * sway
                val y = headY + sin(a) * dist * ease + cos(a) * sway
                val alpha = when {
                    u < 0.15f -> u / 0.15f
                    u > 0.6f -> (1f - u) / 0.4f
                    else -> 1f
                }
                val scale = 0.75f + 0.55f * ease
                val tilt = (if (i % 2 == 0) -12f else 10f) + sway * 4f
                drawNote(x * k, y * k, k * scale, tilt, Color.White.copy(alpha = alpha))
            }
        }
    }
}

/** An eighth note (♪) with its head at ([x], [y]), [unit] px per grid unit, leaning [tiltDeg]. */
internal fun DrawScope.drawNote(x: Float, y: Float, unit: Float, tiltDeg: Float, color: Color) {
    rotate(tiltDeg, pivot = Offset(x, y)) {
        // Head: a slightly tilted oval.
        rotate(-20f, pivot = Offset(x, y)) {
            drawOval(color, topLeft = Offset(x - 1.6f * unit, y - 1.15f * unit), size = Size(3.2f * unit, 2.3f * unit))
        }
        val stemX = x + 1.35f * unit
        val top = y - 6.5f * unit
        drawLine(color, Offset(stemX, y - 0.3f * unit), Offset(stemX, top), strokeWidth = 0.9f * unit, cap = StrokeCap.Round)
        // Flag.
        val flag = Path().apply {
            moveTo(stemX, top)
            cubicTo(stemX + 1.2f * unit, top + 1.2f * unit, stemX + 3.2f * unit, top + 1.6f * unit, stemX + 2.4f * unit, top + 4.2f * unit)
        }
        drawPath(flag, color, style = Stroke(width = 0.9f * unit, cap = StrokeCap.Round))
    }
}

/**
 * The heart, beating for its animation: a strong "lub" then a softer "dub"
 * (with a little dip between them), then one more beat that settles just as
 * the animation ends, so the glass and filled heart follow straight on. It stays an outline until
 * the beat is done, then fills in.
 */
@Composable
internal fun HeartGlyph(t: Float) {
    fun bump(at: Float, width: Float): Float {
        val x = (t - at) / width
        return exp(-0.5f * x * x)
    }
    // Starts from a slight squeeze, so the first beat has something to push off.
    val scale = 1f -
        0.10f * bump(0.04f, 0.035f) +
        0.34f * bump(0.17f, 0.055f) -
        0.08f * bump(0.29f, 0.04f) +
        0.22f * bump(0.42f, 0.06f) -
        // ...and one more beat, ending right as the glass and filled heart come in.
        0.07f * bump(0.60f, 0.05f) +
        0.30f * bump(0.80f, 0.065f)
    Icon(
        // Outline while it beats; it fills in (with the glass) once the beat is done.
        painterResource(Res.drawable.ic_heart),
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale },
    )
}

/**
 * The loop icon drawn live, for its animation: its two arrows sit on one rounded
 * loop (the icon's outline), and they chase each other once around it, back to
 * where they started. They keep their length, so the gap between each head and
 * the next tail stays the same all the way round. At [p] = 0 and 1 it's
 * the normal icon.
 */
@Composable
internal fun LoopGlyph(p: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.minDimension / 24f // the icon's 24x24 grid
        // Regular weight while it moves; the bold icon (and the glass) come in once it's done.
        val stroke = Stroke(width = 2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eased = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it } / 2f
        val shift = LOOP_PERIMETER * eased
        // Each arrow at rest: tail 3 units up the left side, 17.7 long (the other is half a lap on).
        for (tail0 in floatArrayOf(3f, 3f + LOOP_PERIMETER / 2f)) {
            val head = tail0 + LOOP_ARROW + shift
            val tail = head - LOOP_ARROW
            val n = 60
            val pts = Array(n + 1) { i -> loopPoint(tail + (head - tail) * i / n) }
            val body = Path().apply {
                moveTo(pts[0].x * k, pts[0].y * k)
                for (i in 1..n) lineTo(pts[i].x * k, pts[i].y * k)
            }
            drawPath(body, Color.White, style = stroke)
            val a = pts[n - 2]; val h = pts[n]
            val angle = atan2(h.y - a.y, h.x - a.x)
            val wing = 3.5f
            val arrow = Path().apply {
                moveTo((h.x - wing * cos(angle - PI.toFloat() / 4f)) * k, (h.y - wing * sin(angle - PI.toFloat() / 4f)) * k)
                lineTo(h.x * k, h.y * k)
                lineTo((h.x - wing * cos(angle + PI.toFloat() / 4f)) * k, (h.y - wing * sin(angle + PI.toFloat() / 4f)) * k)
            }
            drawPath(arrow, Color.White, style = stroke)
        }
    }
}

/**
 * The loop icon's track: a rounded rectangle from (4,7) to (20,17), corner radius 3,
 * walked clockwise starting at (4,14), the bottom of its left side.
 */
internal val LOOP_PERIMETER: Float = 2f * 4f + 2f * 10f + 2f * 3f * PI.toFloat() // two sides, top and bottom, four quarter-circles
/** Length of each arrow's line at rest. */
internal val LOOP_ARROW: Float = 1f + 1.5f * PI.toFloat() + 12f

/** The point [distance] along the loop track (wraps around). */
internal fun loopPoint(distance: Float): Offset {
    val quarter = 1.5f * PI.toFloat() // a quarter-circle of radius 3
    var d = ((distance % LOOP_PERIMETER) + LOOP_PERIMETER) % LOOP_PERIMETER
    fun arc(cx: Float, cy: Float, from: Float): Offset {
        val a = from + (d / quarter) * (PI.toFloat() / 2f)
        return Offset(cx + 3f * cos(a), cy + 3f * sin(a))
    }
    // left side up, top-left corner, top, top-right corner, right side down,
    // bottom-right corner, bottom, bottom-left corner
    if (d <= 4f) return Offset(4f, 14f - d); d -= 4f
    if (d <= quarter) return arc(7f, 10f, PI.toFloat()); d -= quarter
    if (d <= 10f) return Offset(7f + d, 7f); d -= 10f
    if (d <= quarter) return arc(17f, 10f, 1.5f * PI.toFloat()); d -= quarter
    if (d <= 4f) return Offset(20f, 10f + d); d -= 4f
    if (d <= quarter) return arc(17f, 14f, 0f); d -= quarter
    if (d <= 10f) return Offset(17f - d, 17f); d -= 10f
    return arc(7f, 14f, PI.toFloat() / 2f)
}

/**
 * The shuffle icon drawn live, for its animation. The two lines slither like
 * snakes: led by their arrowheads, each one bends across to where the other
 * line was and back, the movement rippling down its body from head to tail,
 * with a small wiggle travelling along it. At [p] = 0 and 1 it's the normal
 * (bold) icon.
 */
@Composable
internal fun ShuffleGlyph(p: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.minDimension / 24f // the icon's 24x24 grid
        // Regular weight while it moves; the bold icon (and the glass) come in once it's done.
        val stroke = Stroke(width = 2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Head (u = 1, the arrow) moves first; the tail (u = 0) follows this far behind.
        val lag = 0.55f
        // Wiggle: strongest mid-animation, gone at the start and end.
        val wiggle = 1.1f * sin(PI.toFloat() * p)

        fun smooth(x: Float) = x * x * (3f - 2f * x)

        /** One snake: at rest it runs from [yLeft] to [yRight]; it swings to [yLeft2] → [yRight2] and back. */
        fun snake(yLeft: Float, yRight: Float, yLeft2: Float, yRight2: Float, phase: Float) {
            val n = 48
            val xs = FloatArray(n + 1)
            val ys = FloatArray(n + 1)
            for (i in 0..n) {
                val u = i / n.toFloat()
                val x = 3f + 17f * u
                // This point's own progress: the head is ahead of the tail.
                val q = ((p * (1f + lag)) - lag * (1f - u)).coerceIn(0f, 1f)
                val swap = sin(PI.toFloat() * q) // 0 → 1 (swapped) → 0
                val a = yLeft + (yLeft2 - yLeft) * swap
                val b = yRight + (yRight2 - yRight) * swap
                val bend = smooth(((x - 6.5f) / 11f).coerceIn(0f, 1f))
                val base = a + (b - a) * bend
                // A ripple running from head to tail, fading out at the very tail.
                val ripple = wiggle * u * sin(2f * PI.toFloat() * (1.6f * u - 2.2f * p) + phase)
                xs[i] = x
                ys[i] = base + ripple
            }
            val body = Path().apply {
                moveTo(xs[0] * k, ys[0] * k)
                for (i in 1..n) lineTo(xs[i] * k, ys[i] * k)
            }
            drawPath(body, Color.White, style = stroke)
            // Arrowhead, pointing the way the head is going.
            val angle = atan2(ys[n] - ys[n - 2], xs[n] - xs[n - 2])
            val wing = 3.5f
            val hx = xs[n]; val hy = ys[n]
            val head = Path().apply {
                moveTo((hx - wing * cos(angle - PI.toFloat() / 4f)) * k, (hy - wing * sin(angle - PI.toFloat() / 4f)) * k)
                lineTo(hx * k, hy * k)
                lineTo((hx - wing * cos(angle + PI.toFloat() / 4f)) * k, (hy - wing * sin(angle + PI.toFloat() / 4f)) * k)
            }
            drawPath(head, Color.White, style = stroke)
        }
        // At rest: one line runs top-left (7) → bottom-right (16), the other bottom-left (17) →
        // top-right (7). Each slithers over to where the other was and back, in opposite phase.
        snake(7f, 16f, 17f, 7f, phase = 0f)
        snake(17f, 7f, 7f, 16f, phase = PI.toFloat())
    }
}
