package app.amethyst.bubble

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Amber's fire, shared by the bubble (an Android View) and the home screen's orb (Compose).
 * Ember for rest, listening and failure - sparks that rise with the voice; Fuse for working and
 * success - a spark running around the rim and one burst when the text is in. See the design
 * lab, direction "Ember" with Fuse's transcribing and done states.
 */
object Fire {
    const val AMBER = 0xFFFFB020.toInt()
    const val EMBER_RED = 0xFFD9480F.toInt()

    /** The pale fuse head and the burst. */
    const val SPARK = 0xFFFFD58A.toInt()
    const val ERROR = 0xFFF07167.toInt()
    const val INK = 0xFF0A0A0A.toInt()

    /** The disc's radius as a share of half the bubble, leaving room for the fuse on the rim. */
    const val DISC = 0.70f

    /** The fuse runs this far out from the disc's centre, in disc radii. */
    const val FUSE_RADIUS = 1.2f

    /** How far above the bubble the sparks can rise, in bubble sizes. */
    const val SPARK_HEADROOM = 0.7f

    const val FUSE_LAP_MS = 1_100f
    const val BURST_MS = 320f

    /** Alpha of the transcribing fuse's tail segments, head first. */
    val FUSE_TAIL = floatArrayOf(0.85f, 0.55f, 0.32f, 0.16f)
    const val FUSE_SEGMENT = 0.3f

    /** Linear colour mix, [t] clamped to 0..1. */
    fun mix(from: Int, to: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((from shr shift) and 0xFF) * (1 - u) + ((to shr shift) and 0xFF) * u).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun easeOut(t: Float): Float = 1 - (1 - t.coerceIn(0f, 1f)).pow(3)

    /**
     * The flame glyph as path operations, so the View's Path and Compose's Path are built from
     * one definition. [cx]/[cy] is the glyph's centre, [r] the disc radius. Outer flame in ink,
     * inner tongue cut back out in the disc's colour - without it the silhouette reads as a drop.
     */
    fun flame(cx: Float, cy: Float, r: Float, inner: Boolean, ops: FlameOps) {
        val scale = if (inner) 0.5f else 1f
        val base = cy + r * 0.2f + if (inner) r * 0.06f else 0f
        val rb = r * 0.22f * scale
        val h = r * 0.62f * scale
        val tipY = base - h
        val p = ((h - rb) / (rb * 0.7f)).coerceIn(0f, 1f)
        val c1x = rb * (0.552f * (1 - p) + 0.18f * p)
        val c1y = tipY + p * h * 0.28f
        val c2y = base - rb * 0.552f - p * (h - rb) * 0.3f
        ops.moveTo(cx, tipY)
        ops.cubicTo(cx + c1x, c1y, cx + rb, c2y, cx + rb, base)
        ops.arcTo(cx - rb, base - rb, cx + rb, base + rb, 0f, 180f)
        ops.cubicTo(cx - rb, c2y, cx - c1x, c1y, cx, tipY)
        ops.close()
    }

    interface FlameOps {
        fun moveTo(x: Float, y: Float)
        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
        fun arcTo(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float)
        fun close()
    }
}

/**
 * Ember's sparks: a fixed pool of [POOL], so a frame allocates nothing. Coordinates are in the
 * pixels of whatever surface draws them; [size] is the bubble's size there.
 */
class SparkField(private val random: Random = Random(7)) {

    private class Spark {
        var on = false
        var x0 = 0f
        var y0 = 0f
        var rise = 0f
        var drift = 0f
        var phase = 0f
        var born = 0L
        var life = 0L
        var r0 = 0f
    }

    private val pool = Array(POOL) { Spark() }
    private var pending = 0f

    /** While listening: as many sparks, and as high, as the voice is loud. Silence throws none. */
    fun listen(now: Long, dtMs: Long, level: Float, cx: Float, cy: Float, r: Float, size: Float) {
        pending += level.coerceIn(0f, 1f).pow(1.4f) * RATE_PER_S * dtMs / 1000f
        while (pending >= 1f) {
            pending -= 1f
            emit(now, level, cx, cy, r, size)
        }
    }

    /** One spark off the disc's upper edge, e.g. when the bubble appears. */
    fun emit(now: Long, level: Float, cx: Float, cy: Float, r: Float, size: Float) {
        val spark = pool.firstOrNull { !it.on } ?: pool.minBy { it.born }
        val a = (-PI / 2 + (random.nextFloat() - 0.5f) * 1.5f).toFloat()
        spark.on = true
        spark.x0 = cx + cos(a) * r * 0.9f
        spark.y0 = cy + sin(a) * r * 0.9f
        spark.rise = min(size * 0.68f, size * (0.22f + 0.4f * level) * (0.75f + 0.5f * random.nextFloat()))
        spark.drift = cos(a) * size * 0.08f
        spark.phase = (random.nextFloat() * 2 * PI).toFloat()
        spark.born = now
        spark.life = (480 + random.nextFloat() * 320).toLong()
        spark.r0 = size * (0.028f + 0.012f * random.nextFloat())
    }

    /** Failure puts the fire out at once. */
    fun clear() {
        pool.forEach { it.on = false }
        pending = 0f
    }

    /**
     * Hands each living spark to [draw] as a short vertical stroke from (x, y) down by [length],
     * cooling from amber to ember red. Returns whether any spark is still alive.
     */
    fun forEachAlive(
        now: Long,
        size: Float,
        draw: (x: Float, y: Float, length: Float, width: Float, alpha: Float, color: Int) -> Unit,
    ): Boolean {
        var alive = false
        for (spark in pool) {
            if (!spark.on) continue
            val u = (now - spark.born).toFloat() / spark.life
            if (u >= 1f || u < 0f) {
                spark.on = false
                continue
            }
            alive = true
            val y = spark.y0 - spark.rise * (1 - (1 - u) * (1 - u))
            val x = spark.x0 + spark.drift * u + sin(u * 2 * PI.toFloat() + spark.phase) * size * 0.012f
            draw(
                x,
                y,
                spark.r0 * (0.6f + 2.4f * (1 - u)),
                spark.r0 * (1 - u * 0.5f),
                1 - u * u,
                Fire.mix(Fire.AMBER, Fire.EMBER_RED, u * 1.2f),
            )
        }
        return alive
    }

    private companion object {
        const val POOL = 12
        const val RATE_PER_S = 11f
    }
}
