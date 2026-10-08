package io.github.dilshodbekmx.passwordvault.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Build plan §12, 4.3b, [device]: the launcher icon as the phone has it (docs/design/looks/README.md
 * "The icon and the name": the shield with a keyhole, white on #1D4E89, an adaptive icon with a
 * monochrome layer, and bitmaps for Android 7).
 *
 * - From Android 8 the app's icon is an adaptive icon whose packaged resource names a background, a
 *   foreground and a monochrome layer; from Android 13, where a launcher can use it, the system
 *   hands the monochrome layer out.
 * - Android 7 has no adaptive icons: there the icon is a bitmap, and the app has one of the right
 *   size for every screen density, square and round. (On Android 8 and later the system never gives
 *   out those bitmaps, so they are read on Android 7 only; `IconResourcesTest` checks the files.)
 * - Either way it is the shield: white where the shield is, #1D4E89 through its keyhole.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The app's icon, as a launcher asks the system for it. */
    private fun appIcon(): Drawable = context.packageManager.getApplicationIcon(context.applicationInfo)

    @Test
    fun theAppsIconIsTheLauncherIcon() {
        assertEquals("ic_launcher", context.resources.getResourceEntryName(context.applicationInfo.icon))
        assertEquals("mipmap", context.resources.getResourceTypeName(context.applicationInfo.icon))
        assertEquals("ic_launcher_round", context.resources.getResourceEntryName(R.mipmap.ic_launcher_round))
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun theLauncherIconIsAnAdaptiveIconWithAMonochromeLayer() {
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val name = context.resources.getResourceEntryName(id)
            val icon = ResourcesCompat.getDrawable(context.resources, id, context.theme)
            assertTrue("$name is ${icon?.javaClass?.name}", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            assertNotNull("$name: background", icon.background)
            assertNotNull("$name: foreground", icon.foreground)
            // The resource as it was packaged names the three layers.
            assertEquals(
                "$name's layers",
                mapOf("background" to "ic_launcher_background", "foreground" to "ic_launcher_foreground", "monochrome" to "ic_launcher_monochrome"),
                layers(id),
            )
            // And it draws the shield, white on the blue of the mock-up, the keyhole a hole. An
            // adaptive icon shows the middle 72 of its layers' 108 units, under the phone's mask.
            val drawn = draw(icon, SIZE)
            fun shown(unit: Float) = ((unit - 18f) / 72f * SIZE).toInt()
            assertEquals("$name: the background's colour", BLUE, drawn.getPixel(SIZE / 2, shown(21f)))
            assertEquals("$name: the shield", Color.WHITE, drawn.getPixel(SIZE / 2, shown(35f)))
            assertEquals("$name: the keyhole", BLUE, drawn.getPixel(SIZE / 2, shown(KEYHOLE)))
        }
        assertTrue("the app's icon is ${appIcon().javaClass.name}", appIcon() is AdaptiveIconDrawable)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun theSystemHandsOutTheMonochromeLayer() {
        val monochrome = (appIcon() as AdaptiveIconDrawable).monochrome
        assertNotNull("the monochrome layer", monochrome)
        // Its shape is the shield with the keyhole cut out: a launcher tints whatever is not clear.
        val drawn = draw(monochrome!!, SIZE)
        fun layer(unit: Float) = (unit / 108f * SIZE).toInt()
        assertEquals("outside the shield", 0, Color.alpha(drawn.getPixel(layer(20f), layer(20f))))
        assertEquals("the shield", 255, Color.alpha(drawn.getPixel(SIZE / 2, layer(35f))))
        assertEquals("the keyhole", 0, Color.alpha(drawn.getPixel(SIZE / 2, layer(KEYHOLE))))
    }

    @Test
    @SdkSuppress(maxSdkVersion = 25)
    fun android7HasABitmapOfEverySize() {
        // Asked for by density, as a launcher asks for the icon of its screen.
        for ((density, pixels) in BITMAPS) {
            for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
                val name = "${context.resources.getResourceEntryName(id)} at $density dpi"
                val drawable = ResourcesCompat.getDrawableForDensity(context.resources, id, density, null)
                assertTrue("$name is ${drawable?.javaClass?.name}", drawable is BitmapDrawable)
                val bitmap = (drawable as BitmapDrawable).bitmap
                assertEquals("$name: width", pixels, bitmap.width)
                assertEquals("$name: height", pixels, bitmap.height)
                assertEquals("$name: its corner is clear", 0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals("$name: the shape's colour", BLUE, bitmap.getPixel(pixels / 2, (pixels * 0.08f).toInt()))
                assertEquals("$name: the shield", Color.WHITE, bitmap.getPixel(pixels / 2, (pixels * 0.30f).toInt()))
                assertEquals("$name: the keyhole", BLUE, bitmap.getPixel(pixels / 2, (pixels * 0.47f).toInt()))
            }
        }
        // The square icon has corners where the round one has none.
        fun largest(id: Int) = (ResourcesCompat.getDrawableForDensity(context.resources, id, DisplayMetrics.DENSITY_XXXHIGH, null) as BitmapDrawable).bitmap
        assertEquals(BLUE, largest(R.mipmap.ic_launcher).getPixel(30, 30))
        assertEquals(0, Color.alpha(largest(R.mipmap.ic_launcher_round).getPixel(30, 30)))

        // And the app's icon on this phone is one of them.
        val icon = appIcon()
        assertTrue("the app's icon is ${icon.javaClass.name}", icon is BitmapDrawable)
        val bitmap = (icon as BitmapDrawable).bitmap
        assertTrue("its size ${bitmap.width} is one of ${BITMAPS.values}", bitmap.width in BITMAPS.values && bitmap.width == bitmap.height)
        assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.30f).toInt()))
        assertEquals(BLUE, bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.47f).toInt()))
    }

    /** The logo for the Welcome and lock screens is the same drawing, alone: the keyhole stays a hole. */
    @Test
    fun theLogoIsTheShieldWithItsKeyhole() {
        val logo = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_app_logo, context.theme)!!
        val drawn = draw(logo, SIZE)
        // The drawing is 55.2 units high and fills the logo's square from top to bottom.
        fun logo(unit: Float) = ((unit - 26.4f) / 55.2f * SIZE).toInt()
        assertEquals("the logo's corner", 0, Color.alpha(drawn.getPixel(4, 4)))
        assertEquals("the shield", Color.WHITE, drawn.getPixel(SIZE / 2, logo(35f)))
        assertEquals("the keyhole", 0, Color.alpha(drawn.getPixel(SIZE / 2, logo(KEYHOLE))))
        assertEquals("below the keyhole", Color.WHITE, drawn.getPixel(SIZE / 2, logo(72f)))
    }

    /** [drawable] drawn into a square of [size] pixels on a clear background. */
    private fun draw(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** The layers that the adaptive icon's packaged XML names: tag to drawable. */
    private fun layers(id: Int): Map<String, String> {
        val found = linkedMapOf<String, String>()
        val parser = context.resources.getXml(id)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name != "adaptive-icon") {
                    val drawable = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                    found[parser.name] = context.resources.getResourceEntryName(drawable)
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return found
    }

    private companion object {
        /** The icon's blue, #1D4E89. */
        val BLUE = Color.rgb(0x1D, 0x4E, 0x89)

        /** The size the layers are drawn at for a look at their pixels. */
        const val SIZE = 432

        /** The middle of the keyhole's round part, in the units of the icon's 108-unit canvas (icon.svg). */
        const val KEYHOLE = 49.8f

        /** A launcher icon of 48 dp in every density. */
        val BITMAPS = mapOf(
            DisplayMetrics.DENSITY_MEDIUM to 48,
            DisplayMetrics.DENSITY_HIGH to 72,
            DisplayMetrics.DENSITY_XHIGH to 96,
            DisplayMetrics.DENSITY_XXHIGH to 144,
            DisplayMetrics.DENSITY_XXXHIGH to 192,
        )
    }
}
