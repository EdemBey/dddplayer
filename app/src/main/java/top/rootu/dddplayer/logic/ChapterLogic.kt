package top.rootu.dddplayer.logic

/** Pure chapter navigation helpers, kept out of the Android view model for tests. */
object ChapterLogic {

    /** Vimu-compatible fallback step: files without chapters jump in 5-minute blocks. */
    const val FALLBACK_STEP_MS = 5 * 60 * 1000L

    /** Start of the first chapter strictly after [positionMs], or null on the last one. */
    fun nextChapterStart(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.firstOrNull { it.startMs > positionMs }?.startMs

    /** Within this time after a chapter start, "previous" goes to the chapter before it. */
    private const val PREVIOUS_CHAPTER_THRESHOLD_MS = 3_000L

    /**
     * Start of the chapter before [positionMs]. Inside a chapter this restarts
     * it; within [PREVIOUS_CHAPTER_THRESHOLD_MS] of its start it jumps to the
     * previous chapter, so repeated presses do not loop on the same start.
     */
    fun previousChapterStart(chapters: List<Chapter>, positionMs: Long): Long? {
        val current = chapters.indexOfLast { it.startMs < positionMs }
        if (current < 0) return null
        val target = if (current > 0 && positionMs - chapters[current].startMs < PREVIOUS_CHAPTER_THRESHOLD_MS) current - 1 else current
        return chapters[target].startMs
    }

    /** Next 5-minute block for files without chapters; clamped to the duration when known. */
    fun nextChunk(positionMs: Long, durationMs: Long = 0L): Long {
        val next = (positionMs / FALLBACK_STEP_MS + 1) * FALLBACK_STEP_MS
        return if (durationMs > 0L) next.coerceAtMost(durationMs) else next
    }

    /** Previous 5-minute block for files without chapters; never negative. */
    fun previousChunk(positionMs: Long): Long =
        ((positionMs / FALLBACK_STEP_MS - 1) * FALLBACK_STEP_MS).coerceAtLeast(0L)
}
