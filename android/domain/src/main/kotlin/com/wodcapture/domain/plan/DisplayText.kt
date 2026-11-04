package com.wodcapture.domain.plan

/** Watch display limits (protocol §8, REQ-WP04, REQ-SY06). */
object WatchLimits {
    const val LINE = 28
    const val LINES = 12
    const val PARTS = 6
    const val SETS = 30
    const val SEGMENTS = 200
    const val MOVEMENTS = 30
    const val LOAD_ITEMS = 12
    const val SET_NAME = 20
    const val REP_TEXT = 8
    const val ROUND_LABEL = 16
    const val SEGMENT_LABEL = 12
    const val LEVEL = 12
    const val MESSAGE_BUDGET = 3584
}

/** Formatting helpers for pre-rendered watch text (D2: the watch never formats notation). */
object DisplayText {
    /** `480` → "8:00", `40` → "0:40", `3600` → "1:00:00". */
    fun clock(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * Word-wraps [text] to [width] columns. Wrapping is a rendering choice, not truncation: every
     * word survives. Returns `null` if a single word is wider than [width].
     */
    fun wrap(text: String, width: Int = WatchLimits.LINE): List<String>? {
        if (text.length <= width) return listOf(text)
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.any { it.length > width }) return null
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            if (cur.isEmpty()) cur.append(w)
            else if (cur.length + 1 + w.length <= width) cur.append(' ').append(w)
            else { lines += cur.toString(); cur = StringBuilder(w) }
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        return lines
    }

    /** "21-15-9"; long constant-step ladders as "1-2-3...10" to stay within one line. */
    fun scheme(reps: List<Int>): String {
        if (reps.size >= 5) {
            val step = reps[1] - reps[0]
            val constant = reps.zipWithNext().all { (a, b) -> b - a == step }
            if (constant && step != 0) return "${reps[0]}-${reps[1]}-${reps[2]}...${reps.last()}"
        }
        return reps.joinToString("-")
    }

    /** First line that fits, from most to least descriptive. */
    fun firstFitting(candidates: List<String>, width: Int = WatchLimits.LINE): String? =
        candidates.firstOrNull { it.length <= width }
}
