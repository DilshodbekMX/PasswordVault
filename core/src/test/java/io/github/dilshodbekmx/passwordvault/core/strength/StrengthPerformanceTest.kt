package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.generator.PasswordGenerator
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 2.12: "scoring 5,000 passwords takes under 5 s on this machine". The time is measured
 * from a meter that is already built (building it is a one-time cost, reported here too) and includes
 * the JIT's warm-up: the first password is timed as well as the last.
 */
class StrengthPerformanceTest {

    @Test
    fun scoring5000PasswordsTakesUnderFiveSeconds() {
        val meter = Meters.meter
        val passwords = samplePasswords(5_000)
        assertEquals(5_000, passwords.toSet().size)
        val started = System.nanoTime()
        var easy = 0
        for (password in passwords) if (meter.measure(password).easyToGuess) easy++
        val ms = (System.nanoTime() - started) / 1_000_000
        report("5,000 passwords scored in $ms ms; $easy easy to guess")
        assertTrue("scoring 5,000 passwords took $ms ms", ms < 5_000)
    }

    @Test
    fun theSecurityCheckOf5000AccountsTakesUnderFiveSeconds() {
        val meter = Meters.meter
        val passwords = samplePasswords(5_000, seed = 77)
        val vault = Vault(
            entries = passwords.mapIndexed { i, p ->
                Entry(id = "id-$i", title = TITLES[i % TITLES.size] + " " + i, username = "user$i@mail.uz", password = p, createdAt = i.toLong(), updatedAt = i.toLong())
            },
        )
        val started = System.nanoTime()
        val report = SecurityCheck.run(vault, easyToGuess = { SecurityCheck.isEasyToGuess(it, meter) })
        val ms = (System.nanoTime() - started) / 1_000_000
        report("the check of 5,000 accounts took $ms ms: ${report.weak.size} easy to guess, ${report.reused.size} groups")
        assertTrue("the check of 5,000 accounts took $ms ms", ms < 5_000)
    }

    @Test
    fun buildingTheMeterIsAOneTimeCost() {
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val meter = StrengthMeter.create()
        val ms = (System.nanoTime() - started) / 1_000_000
        System.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        val forms = WordList.load().size
        report("a meter built in $ms ms; the bundled list has $forms forms; the heap grew by about ${(after - before) / 1_048_576} MB")
        assertEquals(4, meter.measure("vowel-shiny-shell-crisp-shaft-cadet").score)
    }

    private fun report(line: String) = println("StrengthPerformanceTest: $line")

    companion object {
        private val TITLES = listOf("Gmail", "Facebook", "Telegram", "Click", "Payme", "Instagram", "Uzum", "Bank", "Yandex", "OLX")

        private val WORDS = listOf(
            "sevgi", "baxt", "lola", "olma", "kitob", "salom", "parol", "dunyo", "yulduz", "quyosh", "Toshkent", "Samarqand",
            "Shahzoda", "Dilnoza", "Sardor", "Jasur", "любовь", "солнце", "привет", "Наташа", "Москва", "dragon", "monkey",
            "football", "princess", "Oʻzbekiston", "Ташкент", "Андижон", "mehnat", "kelajak",
        )

        /**
         * [count] different passwords as a vault holds them: a quarter made by the generator's random
         * mode, a quarter by its words mode, a quarter made up by people (a word, a year or a number, a
         * symbol), a quarter of random letters, digits and Cyrillic of 6 to 24 characters.
         */
        fun samplePasswords(count: Int, seed: Long = 5_000): List<String> {
            val random = FakeRandom(seed)
            val generator = PasswordGenerator(random)
            val out = LinkedHashSet<String>()
            val mixed = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789абвгдежзиклмнопрстуфхцчшщэюяўқғҳ!@#$%"
            while (out.size < count) {
                val password = when (out.size % 4) {
                    0 -> String(generator.generate(GeneratorOptions()))
                    1 -> String(generator.generate(GeneratorOptions(kind = GeneratorOptions.Kind.WORDS, words = 4 + random.int(4))))
                    2 -> {
                        val word = WORDS[random.int(WORDS.size)]
                        val number = if (random.int(2) == 0) (1950 + random.int(76)).toString() else random.int(100_000).toString()
                        word + number + listOf("", "!", ".", "@", "#")[random.int(5)]
                    }
                    else -> String(CharArray(6 + random.int(19)) { mixed[random.int(mixed.length)] })
                }
                out.add(password.lowercase(Locale.ROOT).takeIf { out.size % 8 == 6 } ?: password)
            }
            return out.toList()
        }
    }
}
