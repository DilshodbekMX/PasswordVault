package io.github.dilshodbekmx.passwordvault.core.lock.guard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * lock.md test T5: the model check of the counter, appendix B, ported from its Python line for line.
 * It explores, breadth first, every interleaving of the protocol's steps with kills, power cuts
 * (only writes handed over at least SETTLE ago survive), restarts and waiting, and reports the most
 * wrong PINs in a row (P1, limit ROW) and the most wrong PINs that no right PIN was allowed to
 * forgive (P2, limit TOT).
 *
 * The design holds for limits 2 and 3 with settle 1, forgiving 2 within a window of 3, 34 steps
 * deep, and for limits 3 and 5; the four broken variants are found broken. Each run is compared with
 * what the Python of appendix B printed on this machine (`python3 guard_model.py …`): the two
 * maxima, the number of states it explored, and the witness of every violation, step by step. All
 * seven runs of appendix B's table take about two seconds here (the largest, "3 4 2 30 2 4",
 * explores 706,357 states).
 */
class GuardModelTest {

    @Test
    fun t5_theDesignHoldsForLimits2And3Settle1Forgiving2WithinWindow3At34Steps() {
        val result = GuardModel(2, 3, 1, 34, 2, 3, "aged").explore()
        assertEquals(2, result.wrongInARow)
        assertEquals(3, result.notForgiven)
        assertEquals(26_823, result.states)
        assertEquals("wrong in a row: 2 OK\nnot forgiven  : 3 OK", result.report)
    }

    @Test
    fun t5_theDesignHoldsForLimits3And5() {
        val result = GuardModel(3, 5, 1, 34, 2, 3, "aged").explore()
        assertEquals("wrong in a row: 3 OK\nnot forgiven  : 5 OK", result.report)
        assertEquals(185_609, result.states)
    }

    @Test
    fun t5_theDesignHoldsForLimits3And4WithSettle2() {
        val result = GuardModel(3, 4, 2, 30, 2, 4, "aged").explore()
        assertEquals("wrong in a row: 3 OK\nnot forgiven  : 4 OK", result.report)
        assertEquals(706_357, result.states)
    }

    @Test
    fun t5_checkingWithoutAgeingTheChargeIsFoundBroken() {
        val result = GuardModel(2, 3, 1, 22, 2, 3, "noage").explore()
        val witness = "digit1: charge > digit6 wrong > write wrong > digit1: charge > digit6 wrong > power cut > start > " +
            "digit1: charge > digit6 wrong > write wrong > digit1: charge > digit6 wrong > power cut > start > " +
            "digit1: charge > digit6 wrong > write wrong > digit1: charge > digit6 wrong"
        assertEquals("wrong in a row: 6 VIOLATED: $witness\nnot forgiven  : 6 VIOLATED: $witness", result.report)
        assertEquals(219_636, result.states)
    }

    @Test
    fun t5_checkingBeforeCountingIsFoundBroken() {
        val result = GuardModel(2, 3, 1, 22, 2, 3, "countafter").explore()
        val witness = "submit wrong > write wrong > submit wrong > kill > start > submit wrong > power cut > start > " +
            "submit wrong > write wrong > submit wrong > power cut > start > submit wrong > write wrong > submit wrong > " +
            "power cut > start > submit wrong > write wrong > submit wrong"
        assertEquals("wrong in a row: 9 VIOLATED: $witness\nnot forgiven  : 9 VIOLATED: $witness", result.report)
        assertEquals(113_519, result.states)
    }

    @Test
    fun t5_forgivingWrongPinsOlderThanTheWindowIsFoundBroken() {
        val result = GuardModel(2, 3, 1, 30, 2, 3, "nowindow").explore()
        val witness = "digit1: charge > wait > digit6 wrong > write wrong > digit1: charge > wait > wait > wait > wait > " +
            "digit6 right > write right > digit1: charge > wait > digit6 wrong > write wrong > visit ends > " +
            "digit1: charge > wait > digit6 right > write right > digit1: charge > wait > digit6 wrong > write wrong > " +
            "digit1: charge > wait > digit6 wrong"
        assertEquals("wrong in a row: 2 OK\nnot forgiven  : 4 VIOLATED: $witness", result.report)
        assertEquals(25_317, result.states)
    }

    @Test
    fun t5_keepingTheVisitsListAfterTheVisitEndedIsFoundBroken() {
        val result = GuardModel(2, 3, 1, 30, 2, 3, "stale").explore()
        val witness = "digit1: charge > wait > digit6 wrong > write wrong > visit ends > digit1: charge > wait > " +
            "digit6 right > write right > digit1: charge > wait > digit6 wrong > write wrong > visit ends > " +
            "digit1: charge > wait > digit6 right > write right > digit1: charge > wait > digit6 wrong > write wrong > " +
            "digit1: charge > wait > digit6 wrong"
        assertEquals("wrong in a row: 2 OK\nnot forgiven  : 4 VIOLATED: $witness", result.report)
        assertEquals(30_936, result.states)
    }
}

/**
 * The model of lock.md appendix B. The record on disk is `(row, total, charged, off)` and each write
 * replaces it; `w` holds the writes handed over that a power cut may still lose, after the last
 * settled one. Memory is null after a kill or a power cut; otherwise `idle` (with the times of this
 * visit's last FMAX wrong checks the app remembers), `charged` (when, and that list), `resW` (a
 * wrong result about to be written) or `resR` (a right result, the list already open, its write in
 * the background, with the number the app forgives). `consec` and `unforg` are the truth: wrong
 * checks since the last right PIN, and wrong checks that no right PIN was allowed to forgive, by the
 * true list `vtrue` of this visit. Variants: `aged` (the design), `noage`, `countafter`, `nowindow`,
 * `stale`.
 */
internal class GuardModel(
    private val row: Int,
    private val tot: Int,
    private val settle: Int,
    private val maxDepth: Int,
    private val fmax: Int,
    private val window: Int,
    private val variant: String,
) {
    class Result(val wrongInARow: Int, val notForgiven: Int, val states: Int, val report: String)

    private class Wr(val t: Int, val row: Int, val total: Int, val charged: Boolean, val off: Boolean)

    private sealed class Mem {
        class Idle(val vw: List<Int>) : Mem()

        class Charged(val tc: Int, val vw: List<Int>) : Mem()

        class ResW(val vw: List<Int>) : Mem()

        class ResR(val forgiven: Int) : Mem()
    }

    private class State(
        val t: Int,
        val w: List<Wr>,
        val mem: Mem?,
        val consec: Int,
        val unforg: Int,
        val vtrue: List<Int>,
        val index: Int,
        val depth: Int,
    )

    private val floor = -maxOf(settle, window) - 1
    private val events = ArrayList<String>()
    private val parents = IntArrayList()
    private val eventOf = IntArrayList()

    fun explore(): Result {
        val start = State(0, listOf(Wr(-99, 0, 0, false, false)), Mem.Idle(emptyList()), 0, 0, emptyList(), index = 0, depth = 0)
        parents.add(-1)
        eventOf.add(-1)
        val queue = ArrayDeque<State>()
        queue.addLast(start)
        val seen = HashSet<String>()
        var w1 = 0
        var w1At = 0
        var w2 = 0
        var w2At = 0
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            if (s.consec > w1) {
                w1 = s.consec
                w1At = s.index
            }
            if (s.unforg > w2) {
                w2 = s.unforg
                w2At = s.index
            }
            if (s.depth >= maxDepth) continue
            for ((name, next) in successors(s)) {
                var ww = next.w
                val keep = ww.indices.filter { next.t - ww[it].t >= settle }
                if (keep.isNotEmpty()) ww = ww.subList(keep.last(), ww.size).toList()
                val key = key(next.t, ww, next.mem, next.consec, next.unforg, next.vtrue)
                if (seen.add(key)) {
                    val index = parents.size
                    parents.add(s.index)
                    eventOf.add(eventIndex(name))
                    queue.addLast(State(next.t, ww, next.mem, next.consec, next.unforg, next.vtrue, index, s.depth + 1))
                }
            }
        }
        val line1 = "wrong in a row: $w1 " + if (w1 <= row) "OK" else "VIOLATED: " + path(w1At)
        val line2 = "not forgiven  : $w2 " + if (w2 <= tot) "OK" else "VIOLATED: " + path(w2At)
        return Result(w1, w2, seen.size, "$line1\n$line2")
    }

    private class Next(val t: Int, val w: List<Wr>, val mem: Mem?, val consec: Int, val unforg: Int, val vtrue: List<Int>)

    private fun successors(s: State): List<Pair<String, Next>> {
        val t = s.t
        val w = s.w
        val last = w.last()
        val row0 = last.row
        val total0 = last.total
        val nxt = ArrayList<Pair<String, Next>>()
        fun wr(row: Int, total: Int, charged: Boolean, off: Boolean) = w + Wr(t, row, total, charged, off)
        fun limit(r: Int, tt: Int) = r >= row || tt >= tot
        fun fresh(times: List<Int>) = times.count { t - it <= window }
        fun appFresh(times: List<Int>) = if (variant == "nowindow") times.size else fresh(times)
        fun add(times: List<Int>) = if (fmax > 0) (times + t).takeLast(fmax) else emptyList()

        val mem = s.mem
        if (mem != null) {
            if (mem is Mem.Idle && !last.off && !limit(row0, total0)) {
                val vw = mem.vw
                if (variant == "countafter") {
                    nxt += "submit wrong" to Next(t, w, Mem.ResW(add(vw)), s.consec + 1, s.unforg + 1, add(s.vtrue))
                    nxt += "submit right" to Next(t, w, Mem.ResR(appFresh(vw)), 0, s.unforg - fresh(s.vtrue), emptyList())
                } else {
                    nxt += "digit1: charge" to Next(t, wr(row0, total0, true, false), Mem.Charged(t, vw), s.consec, s.unforg, s.vtrue)
                }
            }
            if (mem is Mem.Charged) {
                val vw = mem.vw
                if (variant == "noage" || t - mem.tc >= settle) {
                    nxt += "digit6 wrong" to Next(t, w, Mem.ResW(add(vw)), s.consec + 1, s.unforg + 1, add(s.vtrue))
                    nxt += "digit6 right" to Next(t, w, Mem.ResR(appFresh(vw)), 0, s.unforg - fresh(s.vtrue), emptyList())
                }
                nxt += "given up: refund" to Next(t, wr(row0, total0, false, false), Mem.Idle(vw), s.consec, s.unforg, s.vtrue)
                nxt += "visit ends: refund" to Next(
                    t, wr(row0, total0, false, false), Mem.Idle(if (variant == "stale") vw else emptyList()), s.consec, s.unforg, emptyList(),
                )
            }
            if (mem is Mem.ResW) {
                val r2 = row0 + 1
                val t2 = total0 + 1
                nxt += "write wrong" to Next(t, wr(r2, t2, false, limit(r2, t2)), Mem.Idle(mem.vw), s.consec, s.unforg, s.vtrue)
            }
            if (mem is Mem.ResR) {
                nxt += "write right" to Next(t, wr(0, maxOf(0, total0 - mem.forgiven), false, false), Mem.Idle(emptyList()), s.consec, s.unforg, s.vtrue)
            }
            if (mem is Mem.Idle && mem.vw.isNotEmpty()) {
                nxt += "visit ends" to Next(t, w, Mem.Idle(if (variant == "stale") mem.vw else emptyList()), s.consec, s.unforg, emptyList())
            }
            nxt += "kill" to Next(t, w, null, s.consec, s.unforg, emptyList())
            val durable = w.filter { t - it.t >= settle }.ifEmpty { listOf(w[0]) }
            nxt += "power cut" to Next(t, listOf(durable.last()), null, s.consec, s.unforg, emptyList())
        } else if (last.charged) {
            val r2 = row0 + 1
            val t2 = total0 + 1
            nxt += "start: charge counted" to Next(t, wr(r2, t2, false, last.off || limit(r2, t2)), Mem.Idle(emptyList()), s.consec, s.unforg, emptyList())
        } else {
            nxt += "start" to Next(t, w, Mem.Idle(emptyList()), s.consec, s.unforg, emptyList())
        }
        nxt += "wait" to Next(t + 1, w, mem, s.consec, s.unforg, s.vtrue)
        return nxt
    }

    /** The key of appendix B: times relative to now and floored, the memory, the truth. */
    private fun key(tt: Int, ww: List<Wr>, m: Mem?, c: Int, u: Int, v: List<Int>): String {
        fun norm(x: Int) = maxOf(x - tt, floor)
        val b = StringBuilder()
        for (wr in ww) b.append('w').append(norm(wr.t)).append(',').append(wr.row).append(',').append(wr.total)
            .append(if (wr.charged) 'C' else 'c').append(if (wr.off) 'O' else 'o')
        when (m) {
            null -> b.append("|N")
            is Mem.Charged -> b.append("|charged,").append(norm(m.tc)).append(m.vw.joinToString(",", "[", "]") { norm(it).toString() })
            is Mem.ResR -> b.append("|resR,").append(m.forgiven)
            is Mem.Idle -> b.append("|idle").append(m.vw.joinToString(",", "[", "]") { norm(it).toString() })
            is Mem.ResW -> b.append("|resW").append(m.vw.joinToString(",", "[", "]") { norm(it).toString() })
        }
        b.append('|').append(c).append('|').append(u).append(v.joinToString(",", "|[", "]") { norm(it).toString() })
        return b.toString()
    }

    private fun eventIndex(name: String): Int {
        val at = events.indexOf(name)
        if (at >= 0) return at
        events += name
        return events.size - 1
    }

    private fun path(index: Int): String {
        val names = ArrayList<String>()
        var i = index
        while (parents[i] >= 0) {
            names += events[eventOf[i]]
            i = parents[i]
        }
        return names.asReversed().joinToString(" > ")
    }

    /** A growable list of ints without boxing: the parent and the event of every state queued. */
    private class IntArrayList {
        private var data = IntArray(1 shl 16)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        operator fun get(index: Int): Int = data[index]
    }
}
