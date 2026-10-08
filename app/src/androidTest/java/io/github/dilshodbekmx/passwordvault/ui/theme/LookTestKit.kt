package io.github.dilshodbekmx.passwordvault.ui.theme

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.fail

// What the device tests of the looks read: the colours that are really on the screen. A test finds
// a node's place on the screen and looks at that pixel in a screenshot of the whole screen
// (`UiAutomation.takeScreenshot`, every API level), so a dialog, a sheet and the screen behind them
// are read the same way, whatever window each is in. The debug build does not set FLAG_SECURE, so
// the screenshot shows the app.

/** The app's own styles: "Phone's colours" comes from the phone. */
val OWN_STYLES = listOf(StyleChoice.BANK_BLUE, StyleChoice.EMERALD, StyleChoice.NIGHT_VAULT)

/** "#1D4E89" for a failure message. */
fun Int.hex(): String = "#%06X".format(this and 0xFFFFFF)

fun Color.hex(): String = toArgb().hex()

/** Whether the two colours are the same but for rounding, each channel within [tolerance]. */
fun near(a: Int, b: Int, tolerance: Int = 3): Boolean =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tolerance &&
        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tolerance &&
        abs((a and 0xFF) - (b and 0xFF)) <= tolerance

/** The whole screen as it is shown now. */
fun screenshot(): Bitmap {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    repeat(5) {
        automation.takeScreenshot()?.let { return it }
        SystemClock.sleep(200)
    }
    error("no screenshot of the screen")
}

/** The output of a shell command, run as the shell user. */
fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
}

/** The point of the screen at the fractions [fx] and [fy] of this node's width and height. */
fun SemanticsNode.screenPoint(fx: Float, fy: Float): Offset =
    Offset(positionOnScreen.x + size.width * fx, positionOnScreen.y + size.height * fy)

/** The node with [tag], in whatever window it is. */
fun ComposeTestRule.node(tag: String): SemanticsNode = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

/** The colour on the screen at the fractions [fx], [fy] of the node [tag]. */
fun ComposeTestRule.pixel(tag: String, fx: Float = 0.5f, fy: Float = 0.5f): Int {
    val point = node(tag).screenPoint(fx, fy)
    return screenshot().getPixel(point.x.roundToInt(), point.y.roundToInt())
}

/**
 * Waits until the screen shows [expected] at the fractions [fx], [fy] of the node [tag], and fails
 * with [what] and the colour that is there when it does not within [timeoutMs]: a frame with the new
 * colours is on the screen a moment after the composition is idle.
 */
fun ComposeTestRule.awaitPixel(tag: String, expected: Color, what: String, fx: Float = 0.5f, fy: Float = 0.5f, timeoutMs: Long = 5_000) {
    val want = expected.toArgb()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var seen: Int
    do {
        waitForIdle()
        seen = pixel(tag, fx, fy)
        if (near(seen, want)) return
        SystemClock.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    fail("$what: the screen shows ${seen.hex()} at $tag ($fx, $fy), not ${want.hex()}")
}

/**
 * Whether some pixel of the part of the node [tag] between the fractions [from] and [to] (of its
 * width and of its height) is [colour], within [tolerance]: for a thin thing whose exact place is
 * not known, such as a digit, an icon or a ring.
 */
fun ComposeTestRule.hasPixel(tag: String, colour: Color, from: Offset, to: Offset, tolerance: Int = 12): Boolean {
    val node = node(tag)
    val a = node.screenPoint(from.x, from.y)
    val b = node.screenPoint(to.x, to.y)
    val shot = screenshot()
    val want = colour.toArgb()
    for (y in a.y.roundToInt()..b.y.roundToInt()) {
        for (x in a.x.roundToInt()..b.x.roundToInt()) {
            if (near(shot.getPixel(x, y), want, tolerance)) return true
        }
    }
    return false
}

/** [hasPixel], waited for up to [timeoutMs]. */
fun ComposeTestRule.awaitHasPixel(tag: String, colour: Color, from: Offset, to: Offset, what: String, timeoutMs: Long = 5_000) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    do {
        waitForIdle()
        if (hasPixel(tag, colour, from, to)) return
        SystemClock.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    fail("$what: no pixel of $tag between $from and $to is ${colour.hex()}")
}

/**
 * Screenshots of the looks for the user (build plan §12, 4.3b): the shell user's `screencap` writes
 * them to [DIR] on the device, where AGP's uninstall after the run leaves them, and the package's
 * agent pulls them into /home/asus/pvwork/shots-look/.
 */
object LookShots {
    const val DIR = "/data/local/tmp/pv-shots-look"

    /** Empties [DIR]. */
    fun start() {
        shell("rm -rf $DIR")
        shell("mkdir -p $DIR")
    }

    /** Saves the screen as [name].png, once the composition is idle and the window has settled. */
    fun take(compose: ComposeTestRule, name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        shell("screencap -p $DIR/$name.png")
    }

    /** The names of the pictures taken. */
    fun taken(): List<String> = shell("ls $DIR").lines().map { it.trim() }.filter { it.endsWith(".png") }

    private const val SETTLE_MS = 600L
}
