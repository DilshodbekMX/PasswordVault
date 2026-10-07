package io.github.dilshodbekmx.passwordvault.core.generator

import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import org.junit.Assert.assertTrue

/**
 * Pearson's chi-square test, for the "unbiased choice over 10⁶ draws" of architecture.md §8.1: the
 * counts of what came out against the probabilities an unbiased generator gives them.
 *
 * The limits are the quantiles 10⁻⁶ and 1 − 10⁻⁶ of the chi-square distribution with the degrees of
 * freedom of the table, by Wilson and Hilferty's approximation. An unbiased generator falls outside
 * them in about 2 runs in a million; the tests use fixed seeds, so a run gives the same result every
 * time. A statistic below the lower limit fails as well: counts more even than chance makes them
 * come from something that is not random, such as taking the characters in turn.
 */
internal object ChiSquare {
    /** The standard normal quantile of 1 − 10⁻⁶. */
    private const val Z = 4.753424

    /** The statistic of [counts] against [probabilities], which sum to 1 and are all above 0. */
    fun statistic(counts: IntArray, probabilities: DoubleArray): Double {
        require(counts.size == probabilities.size) { "one probability per count" }
        val total = counts.sumOf { it.toLong() }.toDouble()
        var sum = 0.0
        for (i in counts.indices) {
            val expected = total * probabilities[i]
            val d = counts[i] - expected
            sum += d * d / expected
        }
        return sum
    }

    /** The statistic of [counts] against as many equally likely outcomes. */
    fun statistic(counts: IntArray): Double = statistic(counts, DoubleArray(counts.size) { 1.0 / counts.size })

    fun upperLimit(degrees: Int): Double = degrees * (1 - 2.0 / (9 * degrees) + Z * sqrt(2.0 / (9 * degrees))).pow(3)

    fun lowerLimit(degrees: Int): Double =
        degrees * maxOf(0.0, 1 - 2.0 / (9 * degrees) - Z * sqrt(2.0 / (9 * degrees))).pow(3)

    /** Fails unless [counts] fit an unbiased choice among as many equally likely outcomes. */
    fun assertUniform(what: String, counts: IntArray) =
        assertFits(what, counts, DoubleArray(counts.size) { 1.0 / counts.size })

    /** Fails unless [counts] fit [probabilities]. */
    fun assertFits(what: String, counts: IntArray, probabilities: DoubleArray) {
        val x = statistic(counts, probabilities)
        val degrees = counts.size - 1
        val low = lowerLimit(degrees)
        val high = upperLimit(degrees)
        assertTrue(
            "$what: chi-square ${round1(x)} with $degrees degrees of freedom, outside ${round1(low)}..${round1(high)}",
            x in low..high,
        )
    }

    /** One decimal, written by Double.toString, which ignores the default locale. */
    private fun round1(x: Double): String = ((x * 10).roundToLong() / 10.0).toString()
}
