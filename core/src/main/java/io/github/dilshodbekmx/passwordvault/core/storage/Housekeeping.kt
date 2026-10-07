package io.github.dilshodbekmx.passwordvault.core.storage

/**
 * Which generations are kept (architecture.md §4.10, [V-S2], [V-S4]): the parts of housekeeping
 * that decide without touching a file. `VaultStore.housekeeping` reads and opens the files, renames
 * the DAMAGED ones to `.bad` and deletes what [deletions] names.
 *
 * The words of §4.10:
 * - **`L`**: the generation opened from disk at the first unlock of this vault in this process. A
 *   vault this process created, restored or made by a replacement has none.
 * - **`W`**: the generations this process wrote. One of them is never verified in this process.
 * - **Verified**: opened from disk by this process (R1 to R10) and not in `W`. `L` is verified.
 *
 * It uses no clock: a clock that jumps changes nothing here.
 */
internal object Housekeeping {
    /** With fewer verified generations of the current `vk_id` than this, nothing is deleted. */
    const val VERIFIED = 5

    /** Of the generations this process wrote, the newest this many are kept. */
    const val WRITTEN = 20

    /** Housekeeping runs after the first durable save of a process and after every this many more. */
    const val SAVES = 20

    /**
     * When housekeeping is due: after the first durable save of the process and after every
     * [SAVES] more, and only when the last save's `syncDir` succeeded [V-S2]. A run that could not
     * finish leaves it due, so the next durable save tries again.
     */
    class Trigger {
        private var durableSaves = 0
        private var atLastRun = -1
        private var lastSaveDurable = false

        /** A save (`commit` or `publish`) renamed its file into place; [durable]: its `syncDir` succeeded. */
        fun saved(durable: Boolean) {
            lastSaveDurable = durable
            if (durable) durableSaves++
        }

        val due: Boolean
            get() = lastSaveDurable && durableSaves > 0 && (atLastRun < 0 || durableSaves - atLastRun >= SAVES)

        /** Housekeeping ran to its end. */
        fun ran() {
            atLastRun = durableSaves
        }
    }

    /** One live generation as housekeeping sees it: its state, and its `vk_id` and writer version when it is OK. */
    class Gen(val number: Long, val state: FileState, val vkId: String?, val writerVersion: Int?)

    /**
     * Step 4 of §4.10: the numbers of the live generations to delete, highest first.
     *
     * - Nothing unless at least [VERIFIED] generations of [current] are in [verified]; "the five"
     *   are the newest [VERIFIED] of them.
     * - Generations of [current] below the lowest of the five, and all but the newest [WRITTEN] of
     *   the generations of [current] in [written].
     * - None below a generation that could not be read, or that a newer app wrote (a file that
     *   could not be read stops all pruning below it).
     * - Never the newest generation of each writer version lower than [runningWriterVersion].
     * - Never a generation that is not OK, and never one of another `vk_id` (§4.10 step 5, §4.11).
     */
    fun deletions(
        gens: List<Gen>,
        current: String,
        verified: Collection<Long>,
        written: Set<Long>,
        runningWriterVersion: Int,
    ): List<Long> {
        val five = verified.filter { n -> gens.any { it.number == n && it.state == FileState.OK && it.vkId == current } }
            .distinct().sortedDescending().take(VERIFIED)
        if (five.size < VERIFIED) return emptyList()
        val lowest = five.last()
        val floor = gens.filter { it.state == FileState.UNREADABLE || it.state == FileState.NEWER }.maxOfOrNull { it.number }
        val pinned = gens.filter { it.state == FileState.OK && it.writerVersion != null && it.writerVersion < runningWriterVersion }
            .groupBy { it.writerVersion }
            .values.map { group -> group.maxOf { it.number } }
            .toSet()
        val deletable = gens.filter {
            it.state == FileState.OK && it.vkId == current && it.number !in pinned && (floor == null || it.number > floor)
        }
        val belowFive = deletable.filter { it.number < lowest && it.number !in written }.map { it.number }
        val oldWritten = gens.filter { it.state == FileState.OK && it.vkId == current && it.number in written }
            .map { it.number }.sortedDescending().drop(WRITTEN)
            .filter { n -> deletable.any { it.number == n } }
        return (belowFive + oldWritten).distinct().sortedDescending()
    }
}
