package io.github.dilshodbekmx.passwordvault.window

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import org.junit.Assert.assertEquals

/**
 * A window's protections as Android reports them (lock.md §13.5), for the window kit's device
 * tests. A switch that this API level does not have is null.
 *
 * @property hidesOverlays read from the window's attributes as `toString()` prints them ("pfl=…
 *   HIDE_NON_SYSTEM_OVERLAY_WINDOWS"), the way `dumpsys window` showed it in the window lab: the
 *   private flag has no public getter.
 */
data class WindowProtections(
    val secure: Boolean,
    val filtersObscuredTouches: Boolean,
    val autofillExcluded: Boolean?,
    val accessibilitySensitive: Boolean?,
    val hidesOverlays: Boolean?,
) {
    /** Every switch of `VaultSecurity.applyToWindow` that this API level has is on; FLAG_SECURE is [secure]. */
    fun assertVaultPolicy(what: String, secure: Boolean) {
        val expected = WindowProtections(
            secure = secure,
            filtersObscuredTouches = true,
            autofillExcluded = if (Build.VERSION.SDK_INT >= 26) true else null,
            accessibilitySensitive = if (Build.VERSION.SDK_INT >= 34) true else null,
            hidesOverlays = if (Build.VERSION.SDK_INT >= 31) true else null,
        )
        assertEquals("$what on API ${Build.VERSION.SDK_INT}", expected, this)
    }
}

/** This window's [WindowProtections]; on the main thread. */
fun Window.protections(): WindowProtections = WindowProtections(
    secure = attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
    filtersObscuredTouches = decorView.filterTouchesWhenObscured,
    autofillExcluded = if (Build.VERSION.SDK_INT >= 26) {
        decorView.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    } else {
        null
    },
    accessibilitySensitive = if (Build.VERSION.SDK_INT >= 34) decorView.isAccessibilityDataSensitive else null,
    hidesOverlays = if (Build.VERSION.SDK_INT >= 31) attributes.toString().contains("HIDE_NON_SYSTEM_OVERLAY_WINDOWS") else null,
)
