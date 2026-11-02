package isomeria.aichat.core

import isomeria.aichat.data.db.Contact

/** 联系人编辑页调整上下文上限：1 万以内每次加减 1000，以上每次 1 万，落在整数档上。 */
object ContextBudgetSteps {
    private const val SMALL_STEP = 1000
    private const val LARGE_STEP = 10_000

    fun increase(value: Int): Int {
        val step = if (value < LARGE_STEP) SMALL_STEP else LARGE_STEP
        return ((value / step + 1) * step).coerceAtMost(Contact.MAX_CONTEXT_TOKENS)
    }

    fun decrease(value: Int): Int {
        val step = if (value <= LARGE_STEP) SMALL_STEP else LARGE_STEP
        return (((value - 1) / step) * step).coerceAtLeast(Contact.MIN_CONTEXT_TOKENS)
    }
}
