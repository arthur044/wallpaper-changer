package io.github.arthur044.wallpaperchanger.core.render

/**
 * The desktop's background color (color_extractor.py): a faithful port of
 * colorthief 0.2.1, i.e. MMCQ (modified median cut quantization, from
 * Leptonica). Kept step for step, quirks included (stable queue order, the
 * second pass's odd target, float averaging), so the same art yields the same
 * color on both platforms. Don't "improve" it: any change breaks parity.
 */
object ColorThief {
    /** color_extractor.py calls get_color(quality=4). */
    const val DESKTOP_QUALITY = 4

    private const val SIGBITS = 5
    private const val RSHIFT = 8 - SIGBITS
    private const val HISTO_SIDE = 1 shl SIGBITS
    private const val HISTO_SIZE = 1 shl (3 * SIGBITS)
    private const val MAX_ITERATION = 1000
    private const val FRACT_BY_POPULATIONS = 0.75
    private const val MIN_ALPHA = 125
    private const val WHITE_ABOVE = 250

    /**
     * get_color: the first palette entry. Null where the desktop fell back to
     * its default color: no usable pixel (all white or transparent) or a failed cut.
     *
     * @param argbPixels row-major ARGB, as Bitmap.getPixels() returns them.
     */
    fun dominantColor(argbPixels: IntArray, quality: Int = DESKTOP_QUALITY): Rgb? =
        palette(argbPixels, colorCount = 5, quality = quality)?.first()

    /** get_palette. Like the original, it may return fewer than [colorCount] colors. */
    fun palette(argbPixels: IntArray, colorCount: Int = 10, quality: Int = 10): List<Rgb>? {
        require(quality >= 1) { "quality must be >= 1" }
        require(colorCount in 2..256) { "colorCount must be in 2..256" }
        val valid = validPixels(argbPixels, quality)
        if (valid.isEmpty()) return null
        return try {
            quantize(valid, colorCount)
        } catch (e: QuantizeException) {
            null
        }
    }

    // Every quality-th pixel that is mostly opaque and not near-white, as packed RGB.
    private fun validPixels(argbPixels: IntArray, quality: Int): IntArray {
        val out = IntArray((argbPixels.size + quality - 1) / quality)
        var n = 0
        for (i in argbPixels.indices step quality) {
            val p = argbPixels[i]
            val a = p ushr 24
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (a >= MIN_ALPHA && !(r > WHITE_ABOVE && g > WHITE_ABOVE && b > WHITE_ABOVE)) {
                out[n++] = (r shl 16) or (g shl 8) or b
            }
        }
        return out.copyOf(n)
    }

    private fun quantize(pixels: IntArray, maxColor: Int): List<Rgb> {
        val histo = IntArray(HISTO_SIZE)
        var rMin = Int.MAX_VALUE
        var rMax = 0
        var gMin = Int.MAX_VALUE
        var gMax = 0
        var bMin = Int.MAX_VALUE
        var bMax = 0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) shr RSHIFT
            val g = ((p shr 8) and 0xFF) shr RSHIFT
            val b = (p and 0xFF) shr RSHIFT
            histo[colorIndex(r, g, b)]++
            rMin = minOf(r, rMin)
            rMax = maxOf(r, rMax)
            gMin = minOf(g, gMin)
            gMax = maxOf(g, gMax)
            bMin = minOf(b, bMin)
            bMax = maxOf(b, bMax)
        }

        val byCount = PQueue { it.count.toLong() }
        byCount.push(VBox(rMin, rMax, gMin, gMax, bMin, bMax, histo))
        iterate(byCount, FRACT_BY_POPULATIONS * maxColor, histo)

        // Re-sort by pixel count times size in color space.
        val byCountTimesVolume = PQueue { it.count.toLong() * it.volume }
        while (byCount.size > 0) byCountTimesVolume.push(byCount.pop())
        iterate(byCountTimesVolume, (maxColor - byCountTimesVolume.size).toDouble(), histo)

        val colors = ArrayList<Rgb>(byCountTimesVolume.size)
        while (byCountTimesVolume.size > 0) colors += byCountTimesVolume.pop().average
        return colors
    }

    private fun iterate(queue: PQueue, target: Double, histo: IntArray) {
        var nColor = 1
        var nIter = 0
        while (nIter < MAX_ITERATION) {
            val vbox = queue.pop()
            if (vbox.count == 0) {
                queue.push(vbox)
                nIter++
                continue
            }
            val (vbox1, vbox2) = medianCutApply(histo, vbox)
            if (vbox1 == null) throw QuantizeException()
            queue.push(vbox1)
            if (vbox2 != null) {
                queue.push(vbox2)
                nColor++
            }
            if (nColor >= target) return
            if (nIter > MAX_ITERATION) return
            nIter++
        }
    }

    private fun medianCutApply(histo: IntArray, vbox: VBox): Pair<VBox?, VBox?> {
        if (vbox.count == 0) return null to null
        if (vbox.count == 1) return vbox to null

        val rw = vbox.r2 - vbox.r1 + 1
        val gw = vbox.g2 - vbox.g1 + 1
        val bw = vbox.b2 - vbox.b1 + 1
        val axis = when (maxOf(rw, gw, bw)) {
            rw -> Axis.R
            gw -> Axis.G
            else -> Axis.B
        }
        val lo = vbox.low(axis)
        val hi = vbox.high(axis)

        // Cumulative pixel count along the cut axis, per plane.
        val partial = IntArray(HISTO_SIDE)
        var total = 0
        for (i in lo..hi) {
            var sum = 0
            for (j in vbox.otherRange(axis, first = true)) {
                for (k in vbox.otherRange(axis, first = false)) {
                    sum += histo[axis.index(i, j, k)]
                }
            }
            total += sum
            partial[i] = total
        }
        // Python's dict.get(i, False): a missing key and a zero count are both falsy.
        fun partialAt(i: Int) = if (i in lo..hi) partial[i] else 0
        fun lookaheadAt(i: Int) = if (i in lo..hi) total - partial[i] else 0

        for (i in lo..hi) {
            if (partial[i] > total / 2.0) {
                val left = i - lo
                val right = hi - i
                var d2 = if (left <= right) {
                    minOf(hi - 1, (i + right / 2.0).toInt())
                } else {
                    maxOf(lo, (i - 1 - left / 2.0).toInt())
                }
                // Avoid 0-count boxes.
                while (partialAt(d2) == 0) {
                    d2++
                    if (d2 > hi) throw QuantizeException()
                }
                var count2 = lookaheadAt(d2)
                while (count2 == 0 && partialAt(d2 - 1) != 0) {
                    d2--
                    count2 = lookaheadAt(d2)
                }
                return vbox.withHigh(axis, d2) to vbox.withLow(axis, d2 + 1)
            }
        }
        return null to null
    }

    private fun colorIndex(r: Int, g: Int, b: Int) = (r shl (2 * SIGBITS)) + (g shl SIGBITS) + b

    private enum class Axis {
        R, G, B;

        // Histogram index for plane i of this axis and (j, k) on the other two, in r-g-b order.
        fun index(i: Int, j: Int, k: Int): Int = when (this) {
            R -> colorIndex(i, j, k)
            G -> colorIndex(j, i, k)
            B -> colorIndex(j, k, i)
        }
    }

    /** A box in the 5-bit-per-channel color space. Immutable: cuts make new boxes. */
    private class VBox(
        val r1: Int,
        val r2: Int,
        val g1: Int,
        val g2: Int,
        val b1: Int,
        val b2: Int,
        private val histo: IntArray,
    ) {
        val volume: Long by lazy { (r2 - r1 + 1).toLong() * (g2 - g1 + 1) * (b2 - b1 + 1) }

        val count: Int by lazy {
            var n = 0
            for (i in r1..r2) for (j in g1..g2) for (k in b1..b2) n += histo[colorIndex(i, j, k)]
            n
        }

        // Summed in the same order and precision as Python, so truncation matches.
        val average: Rgb by lazy {
            var total = 0
            val mult = 1 shl RSHIFT
            var rSum = 0.0
            var gSum = 0.0
            var bSum = 0.0
            for (i in r1..r2) for (j in g1..g2) for (k in b1..b2) {
                val h = histo[colorIndex(i, j, k)]
                total += h
                rSum += h * (i + 0.5) * mult
                gSum += h * (j + 0.5) * mult
                bSum += h * (k + 0.5) * mult
            }
            if (total != 0) {
                Rgb((rSum / total).toInt(), (gSum / total).toInt(), (bSum / total).toInt())
            } else {
                Rgb(
                    (mult * (r1 + r2 + 1) / 2.0).toInt(),
                    (mult * (g1 + g2 + 1) / 2.0).toInt(),
                    (mult * (b1 + b2 + 1) / 2.0).toInt(),
                )
            }
        }

        fun low(axis: Axis) = when (axis) {
            Axis.R -> r1
            Axis.G -> g1
            Axis.B -> b1
        }

        fun high(axis: Axis) = when (axis) {
            Axis.R -> r2
            Axis.G -> g2
            Axis.B -> b2
        }

        // The two axes other than [axis], in r-g-b order, like the Python loops.
        fun otherRange(axis: Axis, first: Boolean): IntRange = when (axis) {
            Axis.R -> if (first) g1..g2 else b1..b2
            Axis.G -> if (first) r1..r2 else b1..b2
            Axis.B -> if (first) r1..r2 else g1..g2
        }

        fun withHigh(axis: Axis, v: Int) = when (axis) {
            Axis.R -> VBox(r1, v, g1, g2, b1, b2, histo)
            Axis.G -> VBox(r1, r2, g1, v, b1, b2, histo)
            Axis.B -> VBox(r1, r2, g1, g2, b1, v, histo)
        }

        fun withLow(axis: Axis, v: Int) = when (axis) {
            Axis.R -> VBox(v, r2, g1, g2, b1, b2, histo)
            Axis.G -> VBox(r1, r2, v, g2, b1, b2, histo)
            Axis.B -> VBox(r1, r2, g1, g2, v, b2, histo)
        }
    }

    /** colorthief's PQueue: stable ascending sort on demand, pop from the end. */
    private class PQueue(private val key: (VBox) -> Long) {
        private val contents = ArrayList<VBox>()
        private var sorted = false

        val size: Int get() = contents.size

        fun push(vbox: VBox) {
            contents += vbox
            sorted = false
        }

        // Python raised IndexError on an empty pop; the desktop caught it and fell back.
        fun pop(): VBox {
            if (contents.isEmpty()) throw QuantizeException()
            if (!sorted) {
                contents.sortBy(key) // stable, like Python's list.sort
                sorted = true
            }
            return contents.removeAt(contents.size - 1)
        }
    }

    private class QuantizeException : Exception()
}
