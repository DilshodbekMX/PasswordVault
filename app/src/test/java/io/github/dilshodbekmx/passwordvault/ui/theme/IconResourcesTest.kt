package io.github.dilshodbekmx.passwordvault.ui.theme

import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The launcher icon's files (build plan §12, 4.3b; docs/design/looks/README.md "The icon and the
 * name"): one drawing, `docs/design/looks/icon/icon.svg`, and one script, `make-icon.py`, which
 * makes every resource from it and records each file's SHA-256 in `generated.sha256`. So that
 * replacing the icon stays one step, the resources in the tree are those the script wrote from the
 * drawing in the tree: a resource changed by hand, or a drawing changed without running the script,
 * fails here. What a phone makes of the resources is the device test `LauncherIconTest`.
 */
class IconResourcesTest {
    private val root = SourceTree.root()
    private val res = File(root, "app/src/main/res")
    private val icon = File(root, "docs/design/looks/icon")

    @Test
    fun theResourcesAreThoseTheScriptMadeFromTheDrawing() {
        val lines = File(icon, "generated.sha256").readLines().filter { it.isNotBlank() }
        val recorded = lines.associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }
        assertEquals("one line per file", lines.size, recorded.size)
        val changed = recorded.filter { (path, digest) -> File(root, path).let { !it.isFile || sha256(it) != digest } }.keys
        assertTrue(
            "not what docs/design/looks/icon/make-icon.py wrote last (change icon.svg and run the script; never edit its output): $changed",
            changed.isEmpty(),
        )
        assertEquals("the drawing and what is made from it", setOf("docs/design/looks/icon/icon.svg") + MADE, recorded.keys)
    }

    @Test
    fun theLauncherIconIsAdaptiveWithAOneColourLayer() {
        for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val adaptive = xml(File(res, "mipmap-anydpi-v26/$name"))
            assertEquals(name, "adaptive-icon", adaptive.tagName)
            assertEquals("@drawable/ic_launcher_background", adaptive.layer("background"))
            assertEquals("@drawable/ic_launcher_foreground", adaptive.layer("foreground"))
            assertEquals("@drawable/ic_launcher_monochrome", adaptive.layer("monochrome"))
        }
        // One drawing: the foreground, the one-colour layer and the logo are the same path.
        val drawn = listOf("ic_launcher_foreground", "ic_launcher_monochrome", "ic_app_logo").map { paths(File(res, "drawable/$it.xml")) }
        assertTrue("the drawing has a path", drawn.first().isNotEmpty())
        assertEquals(drawn[0], drawn[1])
        assertEquals(drawn[0], drawn[2])
        // The launcher's layers fill an adaptive icon's canvas; the logo is the drawing in a tight square.
        for (layer in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val vector = xml(File(res, "drawable/$layer.xml"))
            assertEquals("108", vector.getAttribute("android:viewportWidth"))
            assertEquals("108", vector.getAttribute("android:viewportHeight"))
        }
        val logo = xml(File(res, "drawable/ic_app_logo.xml"))
        assertEquals(logo.getAttribute("android:viewportWidth"), logo.getAttribute("android:viewportHeight"))
        assertTrue(logo.getAttribute("android:viewportWidth").toDouble() < 72.0)
    }

    @Test
    fun androidSevenHasABitmapOfEverySize() {
        for ((density, pixels) in BITMAPS) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val file = File(res, "mipmap-$density/$name.png")
                assertTrue("$file", file.isFile)
                assertEquals("$density $name", pixels to pixels, pngSize(file))
            }
            // One bitmap of each name: a second format would be a second resource of the same name.
            val names = File(res, "mipmap-$density").list().orEmpty().sorted()
            assertEquals(density, listOf("ic_launcher.png", "ic_launcher_round.png"), names)
        }
        assertFalse("Android Studio's template icon is gone", File(res, "mipmap-mdpi/ic_launcher.webp").exists())
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun xml(file: File): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun Element.layer(name: String): String {
        val layers = getElementsByTagName(name)
        assertEquals("one <$name>", 1, layers.length)
        return (layers.item(0) as Element).getAttribute("android:drawable")
    }

    /** The path data of a vector drawable, in order. */
    private fun paths(file: File): List<String> {
        val nodes = xml(file).getElementsByTagName("path")
        return List(nodes.length) { (nodes.item(it) as Element).getAttribute("android:pathData") }
    }

    /** A PNG's width and height, from its header. */
    private fun pngSize(file: File): Pair<Int, Int> = DataInputStream(file.inputStream()).use { input ->
        val signature = ByteArray(8).also(input::readFully)
        assertEquals("a PNG file: $file", listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), signature.map { it.toInt() and 0xFF })
        input.readInt() // the length of the header chunk
        assertEquals("IHDR", String(ByteArray(4).also(input::readFully), Charsets.US_ASCII))
        input.readInt() to input.readInt()
    }

    private companion object {
        /** A launcher icon of 48 dp in each density. */
        val BITMAPS = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)

        /** Every file the script makes. */
        val MADE: Set<String> = setOf(
            "app/src/main/res/drawable/ic_launcher_background.xml",
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
            "app/src/main/res/drawable/ic_launcher_monochrome.xml",
            "app/src/main/res/drawable/ic_app_logo.xml",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
        ) + BITMAPS.keys.flatMap { listOf("app/src/main/res/mipmap-$it/ic_launcher.png", "app/src/main/res/mipmap-$it/ic_launcher_round.png") }
    }
}
