package io.github.dilshodbekmx.passwordvault.ui.list

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.5, [device]: the Passwords screen with 5,000 accounts from `FakeVaultRepository`
 * (ux.md §2 "designed and tested for 5,000 entries", §5.11 "Many entries", §12 check 10) scrolls
 * from the top to the end and back, on every API level; and on API 37 a keystroke in the search
 * field answers within 50 ms (ux.md §5.11: in memory, on every keystroke, no debounce).
 *
 * **What is timed.** From just before the keystroke is sent to the search field
 * (`performTextInput`) until the frame whose composition shows that query's results is applied
 * (a `SideEffect` beside the screen, reading the same state). That is the whole path: the text
 * field, the ViewModel's query, the search on `Dispatchers.Default` over the index of 5,000
 * entries, the new state, and the list composed again. Frames are paced by the test's clock, which
 * advances one frame about every 10 ms while the test waits, much as a 60 Hz display would (16.7 ms).
 * The queries are those of search.md §5's benchmark, typed one letter at a time, after a warm-up
 * round, so that ART has compiled the search code as it will have after a few uses (a baseline
 * profile is left out of version 1, architecture.md §7.3). The debug build is measured, which runs
 * slower than staging or release.
 */
@RunWith(AndroidJUnit4::class)
class ListPerformanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = ListRig(Vault(entries = generatedEntries(ENTRIES)))

    @After
    fun closeRig() = rig.close()

    /** The query each applied composition showed, with the time it was applied, by query. */
    private val composed = ConcurrentHashMap<String, Long>()

    private lateinit var vm: PasswordsViewModel

    private fun showScreen() {
        rig.awaitList()
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { rig.passwordsViewModel() }
                PasswordsScreen(vm, rig.nav)
                val state by vm.state.collectAsStateWithLifecycle()
                SideEffect {
                    val now = SystemClock.elapsedRealtimeNanos()
                    queryOf(state.body)?.let { composed.putIfAbsent(it, now) }
                }
            }
        }
        compose.waitUntil(LIST_TIMEOUT_MS) { vm.state.value.body is ListBody.Browse }
    }

    private fun queryOf(body: ListBody): String? = when (body) {
        is ListBody.Results -> body.typed
        is ListBody.NoResults -> body.typed
        else -> null
    }

    @Test
    fun fiveThousandAccountsScrollToTheEndAndBack() {
        showScreen()
        val body = vm.state.value.body as ListBody.Browse
        assertEquals(ENTRIES, body.total)
        val rows = body.sections.flatMap { it.rows }
        assertEquals(ENTRIES, rows.size)
        compose.onNodeWithTag(ListTags.ALL_ACCOUNTS).assertExists()

        val list = compose.onNodeWithTag(ListTags.LIST)
        for (row in listOf(rows[ENTRIES / 4], rows[ENTRIES / 2], rows[ENTRIES * 3 / 4], rows.last())) {
            list.performScrollToKey(ENTRY_KEY + row.id)
            compose.onNodeWithTag(ListTags.row(row.id)).assertIsDisplayed()
        }
        // Flings by hand in both directions, through the sticky letter headers.
        repeat(FLINGS) { list.performTouchInput { swipeDown() } }
        repeat(FLINGS) { list.performTouchInput { swipeUp() } }
        compose.onNodeWithTag(ListTags.RECENTLY_DELETED).assertDoesNotExist()
        list.performScrollToIndex(0)
        compose.onNodeWithTag(ListTags.SEARCH).assertIsDisplayed()
        assertTrue("favourites are listed first", (vm.state.value.body as ListBody.Browse).favourites.isNotEmpty())
    }

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun aKeystrokeAnswersWithin50MillisecondsOnApi37() {
        showScreen()
        val field = compose.onNodeWithTag(ListTags.SEARCH)
        field.performClick()

        fun typeOneByOne(query: String, times: MutableList<Long>?) {
            field.performTextClearance()
            compose.waitUntil(ANSWER_TIMEOUT_MS) { vm.state.value.body is ListBody.Browse }
            for (end in 1..query.length) {
                val typed = query.substring(0, end)
                composed.remove(typed)
                compose.waitForIdle()
                val start = SystemClock.elapsedRealtimeNanos()
                field.performTextInput(query.substring(end - 1, end))
                compose.waitUntil(ANSWER_TIMEOUT_MS) { composed.containsKey(typed) }
                times?.add(composed.getValue(typed) - start)
            }
        }

        for (query in QUERIES) typeOneByOne(query, null) // warm-up: ART compiles the hot code
        val times = mutableListOf<Long>()
        for (query in QUERIES) typeOneByOne(query, times)

        val sorted = times.sorted()
        val report = String.format(
            Locale.ROOT,
            "%d keystrokes over %d accounts: median %.1f ms, p90 %.1f ms, max %.1f ms",
            sorted.size, ENTRIES, ms(sorted[sorted.size / 2]), ms(sorted[(sorted.size - 1) * 9 / 10]), ms(sorted.last()),
        )
        println("ListPerformanceTest: $report")
        assertTrue("$report; budget 50 ms (ux.md §12 check 10)", ms(sorted[sorted.size / 2]) < BUDGET_MS)
        assertTrue("$report; no keystroke took 100 ms or more", ms(sorted.last()) < 2 * BUDGET_MS)
    }

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0

    private companion object {
        const val ENTRIES = 5_000
        const val FLINGS = 8
        const val BUDGET_MS = 50.0
        const val LIST_TIMEOUT_MS = 60_000L
        const val ANSWER_TIMEOUT_MS = 10_000L

        /** search.md §5's queries, typed one letter at a time. */
        val QUERIES = listOf(
            "o'zbek", "telegram", "сбербанк", "ipak yo'li", "gmail.com", "toshkent", "ўзбекистон", "хизмат",
            "bank karta", "zzzz", "a", "dilshod", "+998", "кабинет", "wi-fi",
        )
    }
}
