package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook
import io.github.dilshodbekmx.passwordvault.ui.theme.PinKeyLook

/** The number of digits of the app's PIN (ux.md §5.6). */
const val PIN_LENGTH = 6

/**
 * The PIN pad of ux.md §5.9 and the PIN steps (§5.6): the dots ([PinDots]) above the 3 × 4 keypad
 * ([PinKeys]). It draws keys and dots and reports key presses; it holds no digit. The ViewModel
 * keeps the digits (lock.md §5.8) and gives the pad only how many there are, so no node of the pad
 * can show one, and nothing of a PIN reaches saved state.
 *
 * A screen that lays the dots and the keys out apart (landscape: dots and texts on the left, the
 * keypad on the right) uses [PinDots] and [PinKeys] itself.
 *
 * @param entered how many digits are held, 0 to [length].
 * @param onDigit a digit key, or a digit on a hardware keyboard.
 * @param onDelete the delete key, or Backspace on a hardware keyboard.
 * @param onFingerprint the fingerprint key, shown only when fingerprint unlock is usable; null
 *   leaves that cell empty.
 * @param onClear a long press on the delete key; null: none.
 * @param enabled false while the PIN is being checked: the keys do nothing.
 * @param wrongTries each increase shakes the dots (unless animations are off) with
 *   `HapticFeedbackType.Reject` (ux.md §5.9 "Wrong PIN").
 */
@Composable
fun PinPad(
    entered: Int,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onFingerprint: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
    wrongTries: Int = 0,
    length: Int = PIN_LENGTH,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        PinDots(entered = entered, length = length, wrongTries = wrongTries)
        PinKeys(onDigit = onDigit, onDelete = onDelete, onFingerprint = onFingerprint, onClear = onClear, enabled = enabled)
    }
}

/**
 * The row of [length] dots, [entered] of them filled; always left to right (ux.md §3). TalkBack
 * reads "PIN, 3 of 6 digits entered" and never a digit (ux.md §8); a polite live region, so that
 * the new count is read after each key. Marked sensitive (ux.md §3).
 *
 * @param wrongTries each increase shakes the row (not when animations are off) with a reject haptic.
 * @param filled a filled dot's colour: the primary colour, or what lies on a panel behind the dots.
 * @param empty an empty dot's ring.
 */
@Composable
fun PinDots(
    entered: Int,
    modifier: Modifier = Modifier,
    length: Int = PIN_LENGTH,
    wrongTries: Int = 0,
    filled: Color = MaterialTheme.colorScheme.primary,
    empty: Color = MaterialTheme.colorScheme.outline,
) {
    val count = entered.coerceIn(0, length)
    val description = pluralStringResource(R.plurals.common_pin_dots, count, count, length)
    val shake = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val step = with(LocalDensity.current) { SHAKE_STEP.toPx() }
    var shownWrongTries by remember { mutableIntStateOf(wrongTries) }
    LaunchedEffect(wrongTries) {
        if (wrongTries == shownWrongTries) return@LaunchedEffect
        shownWrongTries = wrongTries
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        if (animationsOn(context)) {
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 360
                    -step at 45
                    step at 105
                    -step at 165
                    step at 225
                    -step / 2 at 285
                },
            )
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .graphicsLayer { translationX = shake.value }
                .clearAndSetSemantics {
                    testTag = PinPadTags.DOTS
                    contentDescription = description
                    isSensitiveData = true
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(length) { i ->
                val on = i < count
                Box(
                    Modifier
                        .size(DOT_SIZE)
                        .clip(CircleShape)
                        .background(if (on) filled else Color.Transparent)
                        .border(2.dp, if (on) filled else empty, CircleShape),
                )
            }
        }
    }
}

/**
 * The 3 × 4 keypad `1 2 3 / 4 5 6 / 7 8 9 / [fingerprint] 0 [⌫]` (ux.md §5.9): round keys of 72 dp
 * (never under 64 dp), larger with large fonts as far as the width allows, and smaller where the
 * page gives the keypad a height ([KeypadColumn]: the gaps first, to 8 dp, then the keys), digits in
 * `headlineMedium`, a ripple and `HapticFeedbackType.VirtualKey` on each press; no letters under the
 * digits, never shuffled; always left to right. How a key is drawn is the style's
 * (docs/design/looks/README.md "PIN keys", read from `LocalLook`): a digit on a filled circle, alone,
 * or in an outlined circle; the fingerprint key in the primary colour, or on a tonal circle; the
 * delete key always without a fill. Every key is marked sensitive (ux.md §3; if
 * TalkBack cannot press keys that carry the flag, lock.md §13.5 check E12 turns [sensitiveKeys]
 * off and the dots keep it). TalkBack reads "1" … "0", "Delete last digit" and "Unlock with
 * fingerprint" (ux.md §8).
 *
 * A hardware keyboard's digits and Backspace work too: the keypad takes the focus when it appears.
 */
@Composable
fun PinKeys(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onFingerprint: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
    sensitiveKeys: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val digit by rememberUpdatedState(onDigit)
    val delete by rememberUpdatedState(onDelete)
    val keysOn by rememberUpdatedState(enabled)
    val focus = remember { FocusRequester() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier) {
            val fontScale = LocalDensity.current.fontScale
            val preferred = KEY_SIZE * fontScale.coerceIn(1f, MAX_KEY_GROWTH)
            val fits = if (constraints.hasBoundedWidth) (maxWidth - KEY_GAP * 2) / 3 else preferred
            // A page that keeps the whole keypad on the screen gives it a height (KeypadColumn): the
            // gaps go down to 8 dp first, then the keys down to 64 dp (ux.md §8), never further.
            val (keySize, gap) = keySizeAndGap(minOf(preferred, fits), if (constraints.hasBoundedHeight) maxHeight else null)
            val press: (Int) -> Unit = { n ->
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                digit(n)
            }
            Column(
                modifier = Modifier
                    .focusRequester(focus)
                    .onKeyEvent { event -> keysOn && hardwareKey(event, digit, delete) }
                    .focusTarget(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (row in DIGIT_ROWS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (n in row) DigitKey(n, keySize, enabled, sensitiveKeys, press)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    if (onFingerprint != null) {
                        Key(keySize, enabled, sensitiveKeys, PinPadTags.FINGERPRINT, KeyKind.FINGERPRINT, onClick = onFingerprint) {
                            Icon(painterResource(R.drawable.ic_common_fingerprint), contentDescription = stringResource(R.string.common_pin_fingerprint), modifier = Modifier.size(KEY_ICON))
                        }
                    } else {
                        Spacer(Modifier.size(keySize))
                    }
                    DigitKey(0, keySize, enabled, sensitiveKeys, press)
                    Key(
                        size = keySize,
                        enabled = enabled,
                        sensitive = sensitiveKeys,
                        tag = PinPadTags.DELETE,
                        kind = KeyKind.DELETE,
                        onLongClick = onClear,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            delete()
                        },
                    ) {
                        Icon(painterResource(R.drawable.ic_common_backspace), contentDescription = stringResource(R.string.common_pin_delete), modifier = Modifier.size(KEY_ICON))
                    }
                }
            }
            LaunchedEffect(focus) {
                // So that a hardware keyboard's digits reach the pad without a tap first. Nothing
                // is lost if the pad cannot take the focus now.
                try {
                    focus.requestFocus()
                } catch (_: IllegalStateException) {
                }
            }
        }
    }
}

/**
 * The keys' size and the gap between them: [wanted] and 16 dp while the four rows fit in [height]
 * (null: no limit); else the gaps shrink, to 8 dp, then the keys, to [MIN_KEY_SIZE].
 */
private fun keySizeAndGap(wanted: Dp, height: Dp?): Pair<Dp, Dp> {
    if (height == null || wanted * 4 + KEY_GAP * 3 <= height) return wanted.coerceAtLeast(MIN_KEY_SIZE) to KEY_GAP
    val gap = ((height - wanted * 4) / 3).coerceIn(MIN_KEY_GAP, KEY_GAP)
    val size = ((height - gap * 3) / 4).coerceIn(MIN_KEY_SIZE, wanted.coerceAtLeast(MIN_KEY_SIZE))
    return size to gap
}

/** The height of the keypad at its smallest: four rows of 64 dp keys, 8 dp apart. */
val MIN_KEYPAD_HEIGHT: Dp get() = MIN_KEY_SIZE * 4 + MIN_KEY_GAP * 3

/** The test tags of the pad's nodes; none of them names a digit that was entered. */
object PinPadTags {
    const val DOTS = "pv.pin.dots"
    const val DELETE = "pv.pin.delete"
    const val FINGERPRINT = "pv.pin.fingerprint"

    /** The key of the digit [n]. */
    fun key(n: Int): String = "pv.pin.key.$n"
}

@Composable
private fun DigitKey(n: Int, size: Dp, enabled: Boolean, sensitive: Boolean, onPress: (Int) -> Unit) {
    Key(size, enabled, sensitive, PinPadTags.key(n), KeyKind.DIGIT, onClick = { onPress(n) }) {
        Text(text = n.toString(), style = MaterialTheme.typography.headlineMedium)
    }
}

/** The three kinds of key: the style draws each in its own way. */
private enum class KeyKind { DIGIT, FINGERPRINT, DELETE }

/** How one key is drawn: the circle's fill and outline, if it has them, and the colour of what is on it. */
private class KeyColours(val fill: Color?, val outline: Color?, val content: Color)

/**
 * The colours of a key of [kind] in the style being shown. Whatever the style, the digit or icon
 * has a contrast of at least 4.5:1 on what is behind it; a digit key's outline is decoration (the
 * digit says what the key is), so it has the colour of a divider.
 */
@Composable
private fun keyColours(kind: KeyKind): KeyColours {
    val look = LocalLook.current
    val colours = MaterialTheme.colorScheme
    return when (kind) {
        KeyKind.DIGIT -> when (look.pinKeys) {
            PinKeyLook.FILLED -> KeyColours(fill = colours.surfaceContainerHigh, outline = null, content = colours.onSurface)
            PinKeyLook.PLAIN -> KeyColours(fill = null, outline = null, content = colours.onSurface)
            PinKeyLook.OUTLINED -> KeyColours(fill = null, outline = colours.outlineVariant, content = colours.onSurface)
        }
        KeyKind.FINGERPRINT ->
            if (look.fingerprintKeyTonal) {
                KeyColours(fill = colours.secondaryContainer, outline = null, content = colours.onSecondaryContainer)
            } else {
                KeyColours(fill = null, outline = null, content = colours.primary)
            }
        KeyKind.DELETE -> KeyColours(fill = null, outline = null, content = colours.onSurfaceVariant)
    }
}

@Composable
private fun Key(
    size: Dp,
    enabled: Boolean,
    sensitive: Boolean,
    tag: String,
    kind: KeyKind,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colours = keyColours(kind)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (colours.fill != null) Modifier.background(colours.fill) else Modifier)
            .then(if (colours.outline != null) Modifier.border(BorderStroke(KEY_OUTLINE, colours.outline), CircleShape) else Modifier)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .semantics {
                testTag = tag
                if (sensitive) isSensitiveData = true
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colours.content else colours.content.copy(alpha = 0.38f)) {
            content()
        }
    }
}

/** A digit or Backspace typed on a hardware keyboard (ux.md §5.9 "Input"); a held key counts once. */
private fun hardwareKey(event: KeyEvent, onDigit: (Int) -> Unit, onDelete: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) return false
    val code = event.nativeKeyEvent.keyCode
    when (code) {
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> onDigit(code - android.view.KeyEvent.KEYCODE_0)
        in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> onDigit(code - android.view.KeyEvent.KEYCODE_NUMPAD_0)
        android.view.KeyEvent.KEYCODE_DEL -> onDelete()
        else -> return false
    }
    return true
}

/** "Remove animations" in the phone's accessibility settings sets the animator scale to 0 (ux.md §8 "Motion"). */
private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private val DIGIT_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
private val KEY_SIZE = 72.dp
private val MIN_KEY_SIZE = 64.dp
private const val MAX_KEY_GROWTH = 1.5f
private val KEY_GAP = 16.dp
private val MIN_KEY_GAP = 8.dp
private val KEY_ICON = 32.dp
private val KEY_OUTLINE = 1.5.dp
private val DOT_SIZE = 14.dp
private val SHAKE_STEP = 10.dp
