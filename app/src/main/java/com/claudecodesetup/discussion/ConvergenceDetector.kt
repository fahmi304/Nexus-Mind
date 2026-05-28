package com.claudecodesetup.discussion

/**
 * Cheap heuristic for "the speakers have run out of things to say." Signals
 * the orchestrator to stop early. Always conservative: false negatives are
 * fine (we just hit maxTurns), false positives end the discussion too early.
 *
 * Rules (last two completed turns):
 *   1. Both turns are short (<160 chars after trim), AND
 *   2. At least one contains an explicit agreement phrase, AND
 *   3. Neither contains a strong-disagreement phrase that would override.
 *
 * If those all hold, we consider the discussion converged.
 */
object ConvergenceDetector {

    private val AGREE = listOf(
        "i agree", "agreed", "good point", "fair point", "you're right",
        "i concur", "no objections", "nothing to add", "i'm convinced",
        "well said", "no further points"
    )
    private val DISAGREE = listOf(
        "but", "however", "i disagree", "that's wrong", "actually",
        "on the contrary", "i'd push back", "not quite", "incorrect",
        "i'd argue"
    )

    fun isConverged(turns: List<Turn>): Boolean {
        val done = turns.filter { it.status == TurnStatus.DONE }
        if (done.size < 2) return false
        val last = done.takeLast(2)
        val texts = last.map { it.text.trim().lowercase() }
        if (texts.any { it.length > 160 }) return false
        val anyAgree = texts.any { t -> AGREE.any { t.contains(it) } }
        if (!anyAgree) return false
        val anyDisagree = texts.any { t -> DISAGREE.any { t.contains(it) } }
        return !anyDisagree
    }
}
