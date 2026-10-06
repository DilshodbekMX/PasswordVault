# Window and input lab: Android 17 (API 37) emulator

All observations below were made on **one device: API 37** (AVD `Medium_Phone_API_37.0`, Android 17, build `CE2A.260420.019`, x86_64 Google Play image, 1080x2400 at 420 dpi, Gboard as the keyboard, Pixel launcher). The emulator was started read-only on port 5562 and has been stopped. **No other API level was tested**: anything said about API 24-36 comes from source or documentation and is labelled as not tested.

Libraries actually resolved: Compose BOM 2026.09.00, so compose ui / foundation / runtime **1.12.1**, material3 **1.4.0**, activity-compose 1.13.0. Built with AGP 9.4.1, Gradle 9.6.0, Kotlin 2.4.20, compileSdk/targetSdk 37, minSdk 24.

**REPORT.md was not saved.** The harness refused the write with "Subagents should return findings as text, not write report files", so this report exists only here. Everything else is on disk.

Lab directory: `/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/lab/window-input`
- `project/`: copy of the starter project. It builds two apps:
  - `com.example.pvlab.window`, with flavors `perm` and `noperm` that differ only in whether `HIDE_OVERLAY_WINDOWS` is declared.
  - `com.example.pvlab.helper`: a logging autofill service, an overlay drawer, and an accessibility service that does not declare itself an accessibility tool.
- `logs/`: every log quoted here.
- `shots/`: 97 screenshots. The `sheet_*.png` files are contact sheets.
- Scripts: `labctl.py`, `af_scenario.py`, `ime_probe.py`, `e4_overlay.py`, `e5_state.py`, `winflags.sh`, `sheet.py`.

**Incident.** At 12:26 the user's Android Studio ran `adb kill-server` (`/tmp/adb.1000.log`: `blame_kill ... cmdline:/snap/android-studio/247/jbr/bin/java`). After that, emulator-5562 stayed `offline`. I stopped it with `adb -s emulator-5562 emu kill`, started it again read-only (factory fresh) and reinstalled both APKs. Experiments 1 and 2 ran on the first boot. The bottom-sheet autofill check and experiments 3 to 5 ran on the second. No results were lost.

## Short answers

| Question | Answer (API 37) |
|---|---|
| Does FLAG_SECURE blank the app in `screencap`? | Yes. The whole screen captures black, including the keyboard. |
| Do child windows inherit it? | Yes, all of these get `fl=SECURE`: AlertDialog, Dialog, ModalBottomSheet, DropdownMenu, tooltip. The text toolbar (a platform PopupWindow) does **not** get the flag but still captures black, and so does the magnifier. The Snackbar is not a separate window. |
| Does anything show through? | **Toast**: the system draws it and it is captured in full. The system's autofill "Save password to ..." sheet is also captured over the secure app. |
| Recents | With FLAG_SECURE: a white blank card. With only `setRecentsScreenshotEnabled(false)`: a blank card after leaving the app, but the live tile shown right after the app-switch gesture shows real content and can be captured. |
| Are Compose fields offered to autofill by default? | Yes, all 6 test fields. Password-type fields carry `hints=password inputType=0x81`. When the user tapped Save, the service received the typed master password in plain text. |
| Which opt-out guarantees no fill request and no save request? | Two settings together, in **every window**: `Modifier.semantics { contentDataType = ContentDataType.None }` on every field, and removal of the text toolbar's "Autofill" item with `Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }`. ContentDataType.None alone still lets the toolbar's "Autofill" item send a manual request. `importantForAutofill` on the decor view alone does not stop requests. |
| IME_FLAG_NO_PERSONALIZED_LEARNING | Absent by default. `InterceptPlatformTextInput` added it to every field, including fields in a Dialog and in a ModalBottomSheet. It is not experimental in ui 1.12.1 and needs no opt-in. |
| SecureTextField obfuscation | Modes: Visible, RevealLastTyped, Hidden, System. The default is RevealLastTyped: `•••s` while typing, `••••` about 2 s later. |
| filterTouchesWhenObscured | Works when set on the decor view or on the ComposeView. **No effect when set on `LocalView.current` (the AndroidComposeView).** Dialogs need it on their own decor view. |
| setHideOverlayWindows | Hides the overlay when the permission is declared. Without the permission it throws `SecurityException: Permission denial: setHideOverlayWindows: HIDE_OVERLAY_WINDOWS`. |
| Accessibility | Both settings are needed. `decor.setAccessibilityDataSensitive(YES)` hides the window's node tree but **not** the text-changed events. `isSensitiveData` on the fields stops those events. With both, a non-tool accessibility service saw nothing of the app. |
| Saved state | `rememberTextFieldState()` writes the text into the saved-instance-state Bundle in plain text, twice: once as the text and once in the undo history. This includes the password in an OutlinedSecureTextField. Text held by `remember { TextFieldState() }` or by a ViewModel is never written there. |

---

## Experiment 1: screen-capture protection in every kind of window

**What I did**
- MainActivity sets `window.setFlags(FLAG_SECURE, FLAG_SECURE)` before `setContent` when launched with `--ez secure true`.
- Each overlay was opened, either from an intent extra or by tapping, and captured with `adb exec-out screencap -p`.
- PIL computed the fraction of pure-black pixels in each capture.
- Window flags came from `dumpsys window windows` (`winflags.sh`).
- The same steps without FLAG_SECURE served as a control, to show each overlay was really on screen (`shots/sheet_e1_plain.png`).

**Observed (API 37)**

| Window | Window type and flags, `dumpsys window` (secure run) | Black fraction, secure | Control without secure |
|---|---|---|---|
| Activity | `ty=BASE_APPLICATION fl=LAYOUT_IN_SCREEN SECURE ...`; app log `activity window: flags=0x80812100 FLAG_SECURE=true` | 0.999 (only the green battery icon remains) | 0.004 |
| AlertDialog (material3) | own window `ty=APPLICATION fl=DIM_BEHIND SECURE SPLIT_TOUCH HARDWARE_ACCELERATED`; `alert window: flags=0x1802002 FLAG_SECURE=true` | 0.997 | "ALERT SECRET 1234" visible |
| Dialog (compose.ui.window) | `ty=APPLICATION fl=DIM_BEHIND SECURE ...`; `dialog window: flags=0x1802002 FLAG_SECURE=true` | 0.997 | visible |
| ModalBottomSheet | `ty=APPLICATION fl=LAYOUT_IN_SCREEN SECURE ...`; `sheet window: flags=0x81812100 FLAG_SECURE=true` | 0.999 | visible |
| DropdownMenu (Popup) | `Window{... Pop-Up Window} ty=APPLICATION_SUB_PANEL fl=SECURE WATCH_OUTSIDE_TOUCH HARDWARE_ACCELERATED` | 0.999 | "MENU SECRET 1/2" visible |
| Snackbar | no separate window; drawn in the activity window | 0.999 | "SNACKBAR SECRET 1234" visible |
| Tooltip (TooltipBox with PlainTooltip) | `Pop-Up Window ty=APPLICATION_SUB_PANEL fl=NOT_FOCUSABLE SECURE WATCH_OUTSIDE_TOUCH ...` | 0.999 (tooltip region 100 % black) | "TOOLTIP SECRET 1234" visible |
| Text-selection toolbar | `PopupWindow:... ty=APPLICATION_ABOVE_SUB_PANEL fl=NOT_FOCUSABLE LAYOUT_NO_LIMITS SPLIT_TOUCH HARDWARE_ACCELERATED FLAG_LAYOUT_ATTACHED_IN_DECOR`; **no SECURE flag** | 0.999 (black anyway) | "Cut / Copy / Read aloud" visible |
| Magnifier (while dragging a selection) | SurfaceFlinger layer `magnifier surface#767 parentId=756 z=5`, a child layer of the app | 0.999 | magnified "2secretwo" visible |
| Keyboard (Gboard) with a secure field focused | `mInputShown=true` | keyboard area 100 % black | keyboard visible |
| **Toast** | `Window{... Toast} ty=TOAST ... pfl=FIT_INSETS_CONTROLLED TRUSTED_OVERLAY` (a system window) | **0.977; "TOAST SECRET 1234" fully readable** | visible |
| Autofill save prompt (system UI) | system UI | "Save password to LabFill" readable over the black app (`shots/sheet_saveui.png`) | visible |

**Conclusion.** The claim holds on API 37. Every Compose child window received `FLAG_SECURE` through SecureFlagPolicy.Inherit and captured black: Dialog, AlertDialog, ModalBottomSheet, and the Popup-based menu and tooltip. The platform text toolbar and the magnifier are child surfaces of the secure window and also captured black, even though the toolbar window has no SECURE flag of its own. What shows through is the **Toast**, plus system UI such as the autofill save prompt. Never put a secret in a Toast.

**Recents.** Captured after `KEYCODE_APP_SWITCH`; see `shots/sheet_e1_recents.png`.

| Configuration | Live tile (switch straight from the app) | Card after Home, then app switch |
|---|---|---|
| Baseline | full content | full content (snapshot) |
| FLAG_SECURE | captures black (it is the secure live surface) | **blank white card** (only the app-name chip) |
| Only `setRecentsScreenshotEnabled(false)` (API 33+), no FLAG_SECURE | **full content, capturable** | blank white card |

Not observable: what the physical screen shows in the live tile. The emulator has no window, so I only saw what screencap returns.

---

## Experiment 2: autofill

**What I did**
- The helper app's `LabAutofillService` logs every FillRequest and SaveRequest. It walks the AssistStructure and logs, for each node: autofillId, autofillType, autofillHints, inputType, importantForAutofill, idEntry, className, text and autofillValue.
- For every fill request that contains text fields, it answers with a `SaveInfo(PASSWORD|GENERIC)` that lists all of them as optional ids, with `FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`. That is how a password-manager service asks to save.
- Enabled with `adb shell settings put secure autofill_service com.example.pvlab.helper/.LabAutofillService` and `cmd autofill set log_level verbose`.
- `af_scenario.py` focuses fields, types, leaves the screen, and taps "Save" if the system save prompt appears. App-side session starts are counted from the verbose `AutofillManager` log (`startSessionLocked()`).
- The form's fields:
  - A: `OutlinedTextField(rememberTextFieldState())`
  - B: `OutlinedTextField(remember{TextFieldState()})`
  - C: `OutlinedTextField` with ViewModel-held state
  - D: `OutlinedTextField` with `KeyboardOptions(autoCorrectEnabled=false, keyboardType=Password)`
  - E: `OutlinedSecureTextField`
  - F: `SecureTextField(Hidden)`
- The Dialog contains a username OutlinedTextField and an OutlinedSecureTextField. The ModalBottomSheet contains one OutlinedTextField.

**(a) Focusing OutlinedTextField A** (`logs/af_baseline_finish.log`). One fill request was sent, with flags `0x0` and one window. The six Compose fields appear as virtual `android.widget.EditText` nodes:
```
FILL#2 requestId=234 flags=0x0 [] contexts=1 inlineRequest=false
FILL#2     node autofillId=1073741824:i-1@60859449 autofillType=NONE ... className=android.view.ViewGroup ... FOCUSED children=6
FILL#2       node autofillId=1073741824:i55@60859449 autofillType=TEXT hints=null inputType=0x0 importantForAutofill=AUTO idEntry=null className=android.widget.EditText autofillValue=null FOCUSED children=0
FILL#2       node autofillId=1073741824:i85@60859449 autofillType=TEXT hints=password inputType=0x81 importantForAutofill=AUTO idEntry=null className=android.widget.EditText autofillValue=null children=0
FILL#2       node autofillId=1073741824:i95@60859449 autofillType=TEXT hints=password inputType=0x81 ...   (E, OutlinedSecureTextField)
FILL#2       node autofillId=1073741824:i105@60859449 autofillType=TEXT hints=password inputType=0x81 ...  (F, SecureTextField)
```
- D, E and F are tagged as passwords.
- Field values were **never** included in a fill request. In a later manual request, made after `alphaUserAAA` had been typed into A, A still showed `autofillValue=null` (`logs/af_man_base_plain.log`, FILL#16).
- While a session is active, every keystroke goes to the autofill session in system_server: `updateSessionLocked(): id=1073741824:i55, ... value=[type=1, value=12_chars], action=4`.

**(b) Focusing OutlinedSecureTextField E first** (`logs/af_base_e_first_next.log`). The structure was the same, with E marked `FOCUSED` and `flags=0x0`, so FLAG_PASSWORD_INPUT_TYPE is not set. When E was focused after A instead, no second request was sent, because the session already tracked all six ids.

**(c) Leaving the screen after typing.** Three ways of leaving each brought up the system prompt **"Save password to LabFill / No thanks / Save"**:
- finishing the activity;
- navigating in-app so that the fields leave the composition;
- closing a Compose Dialog that contains a password field. Here the prompt appeared immediately: `commit() called by app` (Compose's auto-commit), then `SaveUi: Showing save dialog`.

After the Save button was tapped, the service received the typed text in plain text:
```
SAVE#2       node autofillId=1073741824:i55@60859449 ... text="alphaUserAAA" autofillValue="alphaUserAAA" FOCUSED
SAVE#2       node autofillId=1073741824:i95@60859449 autofillType=TEXT hints=password inputType=0x81 ... text="SuperSecretMasterPw1" autofillValue="SuperSecretMasterPw1"
SAVE#4       node autofillId=1073741826:i131@1659407271 autofillType=TEXT hints=password ... text="DialogSecretPw9" autofillValue="DialogSecretPw9"     (field inside the Compose Dialog)
SAVE#11      node autofillId=1073741826:i131@2119636386 ... text="sheetSecretSSS" autofillValue="sheetSecretSSS"                                  (field inside the ModalBottomSheet)
```
FLAG_SECURE was set in all these runs; it does not stop autofill.

**Conclusion for (a)-(c).** The claim is confirmed on API 37:
- Every Compose text field is an autofill field by default.
- Password-type fields are tagged as passwords.
- The master password reaches the autofill service as soon as the user taps "Save". The system shows that prompt; the app neither shows nor controls it.

**Opt-outs.** All runs used FLAG_SECURE, typed into A and E (and the dialog fields where listed), then finished.

| Setting | App session starts | Fill requests | Save request | Notes |
|---|---|---|---|---|
| none (baseline) | 1 | 1 (full structure) | yes, plain text | |
| (i) `contentDataType = ContentDataType.None` on each field | 0 | 0 | 0 | see the toolbar rows below |
| (ii) decor view `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`, activity window | 2 | **2** (one per focus) | 0 | Each structure is only `FrameLayout importantForAutofill=NO_EXCLUDE_DESCENDANTS children=0`. Log: `canceling session ... when service returned null`, then `notifyValueChanged(...): ignoring on state FINISHED`. |
| (ii) as specified (activity window only), field in a **Dialog** | 1 | 1, with the **dialog window's fields fully listed** | **yes: dialog username and password in plain text** | The activity's decor setting does not cover the dialog window. |
| (ii) on the activity's and the dialog's decor views | 2 | 2 (both windows empty) | 0 | |
| (iii) = (i) + (ii), activity | 0 | 0 | 0 | |
| (iii) on all windows, then tap the text toolbar's **"Autofill"** item | 1 | **1 MANUAL request** (`flags=0x81 [MANUAL_REQUEST\|IME_SHOWING]`) | 0 | Manual requests include not-important views: all fields are listed (`autofillType=NONE`, still `hints=password inputType=0x81`), with values null. |
| (i) only, then the toolbar's "Autofill" item, in the main window and in a Dialog | 1 in each | 1 MANUAL in each | 0 | ContentDataType.None does not remove the toolbar item. |
| (iv) from the Compose docs: `LocalAutofillManager.current?.cancel()` before leaving | 1 | 1 (full structure) | 0 | Stops this save only, and must run before every exit path: finish, back, navigation, dialog close. |
| **(i) + menu filter** (`filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }` at the root of every window). Fields in the main window, a Dialog and a ModalBottomSheet; manual attempts in the main window and the Dialog. | **0** | **0** | **0** | No toolbar popup appeared at all, since its only item was removed. |
| consolidated `VaultSecurity.kt` (below): the same steps plus the sheet | **0** | **0** | **0** | `logs/af_vaultdemo_full.log` |

The toolbar on an empty field with an empty clipboard shows a single **"Autofill"** item after a long press (`shots/crop_dbg_longpress_empty.png`; in a dialog, `crop_dbg_dialog_longpress.png`). Foundation 1.12.1 adds that item to every editable field on API 26+ (`TextFieldSelectionState.android.kt`: `textFieldItem(Autofill, enabled = canShowAutofillMenuItem())`). The manual request it sends does not check ContentDataType.

I fetched the Compose autofill page (developer.android.com/develop/ui/compose/text/autofill) today. It has no opt-out. It documents `contentType` hints, `LocalAutofillManager.commit()` and `cancel()`, and `LocalAutofillHighlightColor`. Of those, only `cancel()` is relevant, and that is (iv) above.

**Recommendation**
- The least intrusive setting that guaranteed no fill request and no save request is **(i) plus (v)**:
  - (i): ContentDataType.None on every text field;
  - (v): the Autofill-item filter at the content root of every window, meaning the activity, each Dialog and each ModalBottomSheet.
- Users see no difference except the missing "Autofill" toolbar item.
- Also set (ii) on every window's decor view as a free safety net. It does not stop requests: a forgotten field still causes a request, but the request carries an empty structure and no values.
- Do not rely on (iv).

---

## Experiment 3: keyboard privacy flags

**What I did.** I focused each field in turn and read `dumpsys input_method`:
- `curEditorInfo` is the copy held by InputMethodManagerService.
- `mInputEditorInfo` is the copy the keyboard process received.
- The two always matched.

Script: `ime_probe.py`. Output: `logs/ime_noflag.txt` and `logs/ime_flag.txt`.

| Field | inputType | imeOptions | IME_FLAG_NO_PERSONALIZED_LEARNING |
|---|---|---|---|
| OutlinedTextField with default KeyboardOptions (main window, Dialog, ModalBottomSheet) | `0x208001` = CLASS_TEXT, AUTO_CORRECT, ENABLE_TEXT_SUGGESTION_SELECTED | `0x2000006` = ACTION_DONE, NO_FULLSCREEN | no |
| OutlinedTextField with `KeyboardOptions(autoCorrectEnabled=false, keyboardType=Password)` | `0x200081` = CLASS_TEXT, VARIATION_PASSWORD, ENABLE_TEXT_SUGGESTION_SELECTED | `0x2000006` | no |
| OutlinedSecureTextField (default), SecureTextField(Hidden), the Dialog's OutlinedSecureTextField | `0x200081` | `0x2000006` | no |
| each of the above inside `InterceptPlatformTextInput`, which adds the flag | unchanged | **`0x3000006`** | **yes, on all 7 fields, including the two in the Dialog and the one in the bottom sheet** |

The app-side log confirms each interception, for example: `EditorInfo intercepted: inputType=0x200081 imeOptions 0x2000006 -> 0x3000006`.

**API status in compose ui 1.12.1**
- The 1.12.1 sources of `InterceptPlatformTextInput`, `PlatformTextInputInterceptor` and `PlatformTextInputMethodRequest` carry **no** `@ExperimentalComposeUiApi` or other opt-in marker.
- A probe file with no `@OptIn` (`OptInProbe.kt`) compiled with no errors and no warnings. The probe also covered `contentDataType`, `ContentDataType`, `isSensitiveData`, `filterTextContextMenuComponents` and `TextContextMenuKeys`, with the same result.
- Installing the interceptor once, around the activity's `setContent` content, was enough. Dialog and ModalBottomSheet content inherits it, as the table shows.

**SecureTextField** (material3 1.4.0, foundation 1.12.1)
- `TextObfuscationMode` offers `Visible`, `RevealLastTyped`, `Hidden` and `System`. Per the KDoc, `System` follows the platform "Show passwords" setting, which on SDK 37+ can differ by input type.
- `SecureTextField` and `OutlinedSecureTextField` default to `RevealLastTyped`. The mask character is U+2022. The default keyboardOptions are `KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)`.
- Observed (`shots/sheet_e3_obfuscation.png`): after typing `pass`, the default field showed `•••s` and, 2.5 s later, `••••`. `Hidden` showed `••••` at once.
- Per the KDoc, `RevealLastTyped` reveals the last character whatever the system setting says. `System` was not tested on the device.
- In the screenshots, Gboard showed a number row on the 0x81 fields E and F.

**Not tested:** whether Gboard actually stops learning when the flag is present. Only that the flag is delivered was verified.

---

## Experiment 4: overlay and touch-filtering switches

**Applying each switch on API 37.** None of these crashed; evidence is the `PvLab` lines in `logs/`.

| Switch | Result |
|---|---|
| `window.decorView.filterTouchesWhenObscured = true` | OK. Side effect: `decor.isAccessibilityDataSensitive` became true without any call to `setAccessibilityDataSensitive`. |
| `.filterTouchesWhenObscured = true` on the ComposeView (`findViewById<ViewGroup>(android.R.id.content).getChildAt(0)`) | OK |
| `LocalView.current.filterTouchesWhenObscured = true` (the AndroidComposeView) | OK, but **no effect** (see the demonstration) |
| `window.setHideOverlayWindows(true)` with `HIDE_OVERLAY_WINDOWS` declared | OK: `activity setHideOverlayWindows(true) OK`, and the window shows `pfl=... HIDE_NON_SYSTEM_OVERLAY_WINDOWS` |
| the same call without the permission (`noperm` flavor) | **Throws** `java.lang.SecurityException: Permission denial: setHideOverlayWindows: HIDE_OVERLAY_WINDOWS`. The lab caught it; uncaught, it would crash the app. |
| `decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)` (API 34+) | OK |
| `Modifier.semantics { isSensitiveData = true }` | OK |
| all of the above on a **Dialog** window and a **ModalBottomSheet** window, reached through `(LocalView.current.parent as DialogWindowProvider).window` | OK: `dialog setHideOverlayWindows(true) OK` and `dialog window: ... decor.filterTouchesWhenObscured=true decor.isAccessibilityDataSensitive=true`; the same for the sheet |

**Touch-filter demonstration** (`e4_overlay.py`)
- The helper app was allowed to draw overlays with `appops set com.example.pvlab.helper SYSTEM_ALERT_WINDOW allow`.
- It draws a `TYPE_APPLICATION_OVERLAY` window over the target button, with `FLAG_NOT_TOUCHABLE` so taps pass through, and alpha 0.5.
- The button is then tapped, and the activity logs `MotionEvent.flags` in `dispatchTouchEvent`.

| App setting | Event reaching the activity | Click registered? |
|---|---|---|
| none | `flags=0x1 WINDOW_IS_OBSCURED=true` | **yes: tapjacking works** |
| filter on the decor view | `flags=0x1` | no (dropped) |
| filter on the ComposeView | `flags=0x1` | no (dropped) |
| filter on LocalView (AndroidComposeView) | `flags=0x1` | **yes: no protection** |
| `setHideOverlayWindows(true)` plus the decor filter | `flags=0x0`; the overlay was not drawn (`dumpsys` isVisible=false) | yes (nothing covered the button) |
| none, overlay requesting alpha 1.0 | `flags=0x1` | **yes.** The window manager capped the overlay at `alpha=0.799805` (`dumpsys input`, `touchOcclusionMode=USE_OPACITY`), so Android's own blocking of untrusted touches did not fire. |
| Dialog button; filter on the activity's decor only | n/a | the tap went through and the dialog closed |
| Dialog button; filter also on the dialog's decor | n/a | dropped; the dialog stayed open |
| Dialog button; `setHideOverlayWindows(true)` | n/a | overlay hidden; normal tap |

**Accessibility data sensitivity**
- The helper's `LabA11yService` declares `isAccessibilityTool="false"`. It was enabled with `settings put secure enabled_accessibility_services ...`.
- The text `a11yVisibleAAA` was typed into field A.

| App setting | Window tree visible to the service | Typed text in the tree | TYPE_VIEW_TEXT_CHANGED event carrying the text |
|---|---|---|---|
| none | yes, all labels | `EDIT:a11yVisibleAAA` | yes: `text=[a11yVisibleAAA]` |
| decor `setAccessibilityDataSensitive(YES)` only | no | no | **yes, still delivered** |
| decor `filterTouchesWhenObscured` only | no | no | **yes, still delivered** |
| `isSensitiveData` on the fields only | labels and buttons visible; the fields are missing | no | no |
| both | **no** | **no** | **no** |

`uiautomator dump` (UiAutomation) still saw every node in all cases, so it cannot be used to check this setting.

**Applying the same to dialogs and bottom sheets**
- Each is a separate window with its own decor view.
- Inside its content, get the window with `(LocalView.current.parent as? DialogWindowProvider)?.window` and set the same switches there. This works for compose `Dialog`, for material3 `AlertDialog` (from inside a slot) and for material3 1.4.0 `ModalBottomSheet`. `VaultWindow` below does exactly this.
- The activity window stays visible underneath a dialog, so `setHideOverlayWindows` on the activity alone already hides overlays while a dialog is up. Setting it on the dialog as well caused no problem.

---

## Experiment 5: what ends up in saved state

**What I did**
- Typed into four fields:
  - `savedAlphaAAA` into (a), backed by `rememberTextFieldState()`;
  - `plainBravoBBB` into (b), backed by `remember { TextFieldState() }`;
  - `vmCharlieCCC` into (c), a ViewModel-held TextFieldState (an extra case);
  - `SecretEchoEEE` into E, an `OutlinedSecureTextField` backed by `rememberTextFieldState()`.
- `onSaveInstanceState` logs the parcel size and every leaf of the Bundle.
- Script: `e5_state.py`. Logs: `logs/e5_rotate.log` and `logs/e5_dontkeep.log`.

| Recreation | (a) rememberTextFieldState | (b) remember{} | (c) ViewModel | E: secure field with rememberTextFieldState |
|---|---|---|---|---|
| Rotation (`accelerometer_rotation 0`, `user_rotation 1`). Log: `onDestroy isFinishing=false isChangingConfigurations=true`, then `onCreate restored=true` | survives | **lost** | survives | survives |
| "Don't keep activities", Home, then return. Log: `LabViewModel cleared`, `onDestroy isFinishing=false isChangingConfigurations=false`, then `onCreate restored=true` | survives | lost | **lost** | survives |

The Bundle written at rotation was 3128 bytes, against 2864 bytes with empty fields:
```
outState/androidx.lifecycle.BundlableSavedStateRegistry.key/SaveableStateRegistry:-1/1qsei4lhprk7h[0][0] = String: savedAlphaAAA
outState/androidx.lifecycle.BundlableSavedStateRegistry.key/SaveableStateRegistry:-1/1qsei4lhprk7h[0][3][0][2] = String: savedAlphaAAA   (undo history)
outState/androidx.lifecycle.BundlableSavedStateRegistry.key/SaveableStateRegistry:-1/1qsei4lhprk7h[1][0] = String: SecretEchoEEE
outState/androidx.lifecycle.BundlableSavedStateRegistry.key/SaveableStateRegistry:-1/1qsei4lhprk7h[1][3][0][2] = String: SecretEchoEEE   (undo history)
```
The values of (b) and (c) never appear in the Bundle. `TextFieldState.Saver` saves `[text, selStart, selEnd, undoManagerState]`, which is why the text is stored twice.

**Method note**
- `adb shell settings put global always_finish_activities 1` alone **did not take effect** on this build. After Home there was no `onDestroy`, and the task later held two activity records.
- Turning on "Don't keep activities" in Developer options did take effect; that switch calls `setAlwaysFinish`. The setting value reads `1` in both cases.
- My first return also used a MAIN/LAUNCHER intent, which stacked a new instance. The valid run returned with the same component-only intent that launched the app.

**Conclusion**
- Never use `rememberTextFieldState()`, or any `rememberSaveable`, for the master password or for entry secrets. It writes them in plain text into the saved-instance-state Bundle, which leaves the app process.
- Keep secrets in a `TextFieldState` held by a ViewModel. In this test that state:
  - survived rotation;
  - was never written to the Bundle;
  - was dropped when the activity was really destroyed.
- Clear it when the vault locks and in `onCleared`.
- `remember { TextFieldState() }` is also safe, but the user loses what they typed on every rotation.
- `android:configChanges` was not tested.

---

## Final code (verified on API 37)

The consolidated file below is **the exact file that was built and run**. `VaultDemoActivity` uses only this API. On the device it gave:
- **Autofill:** 0 sessions, 0 fill requests and 0 save requests, across the main window, a Dialog, a ModalBottomSheet and manual toolbar attempts (`logs/af_vaultdemo_full.log`).
- **Keyboard flag:** `imeOptions=0x3000006` on all 6 fields (main window, Dialog, sheet).
- **Screen capture:** captures 0.997 to 0.999 black. The window shows `fl=... SECURE ...` and `pfl=... HIDE_NON_SYSTEM_OVERLAY_WINDOWS`.
- **Overlay:** the helper's overlay was hidden (`isVisible=false`), and taps arrived with `flags=0x0`.
- **Accessibility:** the non-tool accessibility service saw no app node and received no text-changed event.

`app/src/main/java/com/example/pvlab/window/VaultSecurity.kt`:
```kotlin
package com.example.pvlab.window

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider

/*
 * Consolidated window / input hardening, as verified on an Android 17 (API 37) emulator with
 * Compose BOM 2026.09.00 (ui/foundation 1.12.1, material3 1.4.0). None of these APIs needed an
 * @OptIn.
 *
 * Use:
 *   Activity.onCreate:   applyVaultActivityPolicy()   // before setContent
 *   setContent {         NoPersonalizedLearning { VaultWindow { App() } } }
 *   Dialog / ModalBottomSheet / AlertDialog-slot content:  VaultWindow { ... }
 *   every text field:    Modifier.vaultTextField()
 * Manifest: <uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />
 */

/** Window-level switches for one window (the activity's, or a Compose Dialog's / sheet's). */
fun Window.applyVaultWindowPolicy() {
    // Screenshots, screen recording, casting and the recents thumbnail show this window blank.
    // Compose Dialog / ModalBottomSheet / Popup windows copy it by default (SecureFlagPolicy.Inherit).
    setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    val decor = decorView
    // Drop touches that arrive through another app's window (tapjacking). Must be the decor view
    // or the ComposeView: on LocalView.current (AndroidComposeView) it has no effect.
    decor.filterTouchesWhenObscured = true
    // Safety net only: a field that forgot Modifier.vaultTextField() then yields an empty
    // structure instead of its contents. (It does not stop requests by itself.)
    decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 34) {
        // Hides the window's node tree from accessibility services that are not accessibility
        // tools (TalkBack etc. declare isAccessibilityTool and are not affected).
        decor.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
    if (Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(Manifest.permission.HIDE_OVERLAY_WINDOWS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        // Other apps' TYPE_APPLICATION_OVERLAY windows are hidden while this window is visible.
        // Throws SecurityException if the permission is not declared, hence the check.
        setHideOverlayWindows(true)
    }
}

/** Call from Activity.onCreate, before setContent. */
fun Activity.applyVaultActivityPolicy() {
    window.applyVaultWindowPolicy()
    // Redundant while FLAG_SECURE is set; keeps the recents card blank if FLAG_SECURE is ever
    // lifted (e.g. a user-facing "allow screenshots" option).
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
}

/**
 * Wrap the content of every window in this: the activity's setContent root, and the content of
 * each Dialog, ModalBottomSheet and AlertDialog slot (each is a separate window with its own
 * decor view and its own layout tree).
 *  - For dialog windows it applies [applyVaultWindowPolicy] to the dialog's own Window.
 *  - In every window it removes the "Autofill" item from the text toolbar; that item sends a
 *    manual autofill request even when the field opted out with ContentDataType.None.
 */
@Composable
fun VaultWindow(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.applyVaultWindowPolicy()
        onDispose { }
    }
    Box(Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }) {
        content()
    }
}

/**
 * Apply to every text field (plain and secure):
 *  - ContentDataType.None: Compose never starts an autofill session for the field, so no fill
 *    request and no save request is issued for it.
 *  - isSensitiveData: the field and its text-change events are hidden from non-tool
 *    accessibility services.
 */
fun Modifier.vaultTextField(): Modifier = this.semantics {
    contentDataType = ContentDataType.None
    isSensitiveData = true
}

/**
 * Adds EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING to every text field composed inside it,
 * including fields in Dialog / ModalBottomSheet content (they inherit composition locals).
 * Put it once around the activity's content.
 */
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modifiedRequest = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val inputConnection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions =
                        outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return inputConnection
                }
            }
            nextHandler.startInputMethod(modifiedRequest)
        },
        content = content,
    )
}
```

Usage as it was run, excerpted from `VaultDemoActivity.kt` (the full file is in the artifacts):
```kotlin
class VaultDemoViewModel : ViewModel() {
    val username = TextFieldState()
    val title = TextFieldState()
    val masterPassword = TextFieldState()
    override fun onCleared() { masterPassword.clearText() }
}

class VaultDemoActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyVaultActivityPolicy()
        setContent {
            MaterialTheme {
                NoPersonalizedLearning {
                    VaultWindow { VaultDemoScreen(onFinish = { finish() }) }
                }
            }
        }
    }
}
// fields:   OutlinedSecureTextField(state = vm.masterPassword, label = { Text("Master password") },
//                                   modifier = Modifier.fillMaxWidth().vaultTextField())
// dialogs:  Dialog(onDismissRequest = { ... }) { VaultWindow { /* content with .vaultTextField() fields */ } }
// sheets:   ModalBottomSheet(onDismissRequest = { ... }) { VaultWindow { ... } }
```

App manifest:
```xml
<!-- Normal (install-time) permission, API 31+. Needed by Window.setHideOverlayWindows(true). -->
<uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />
```

Notes for the build:
- `Modifier.vaultTextField()` must go on **every** field. Wrap fields in the app's own composables so it cannot be forgotten.
- Put `VaultWindow` inside **every** Dialog, ModalBottomSheet and AlertDialog slot.
  - Not tested separately: a menu filter placed only at the activity root cannot reach a dialog's separate layout tree; this follows from the code.
  - Observed: without `VaultWindow`, a dialog's decor view gets neither the touch filter nor the accessibility flag.
- A Toast is not protected. Never show a secret in one.

## Not tested / unresolved
These are listed in the `unresolved` field:
- API levels other than 37, and real hardware.
- What the physical screen shows in the recents live tile.
- TalkBack behaviour with these accessibility settings.
- Whether Gboard honours the no-learning flag.
- `TextObfuscationMode.System` on the device.
- `android:configChanges`.
- Other autofill providers.
- The "Read aloud" and other text-processing toolbar items on secret text.
- Saving REPORT.md, which the harness blocked.

## Unresolved

- REPORT.md was not saved. The harness refused to let this subagent write a report file ('Subagents should return findings as text, not write report files'), so the full report is only in this structured output. The orchestrator can write it to .../lab/window-input/REPORT.md.
- Only one device was tested: an Android 17 (API 37) x86_64 emulator with software rendering. Nothing was run on API 24-36 or on real hardware. API-gated calls such as setRecentsScreenshotEnabled (33+), setHideOverlayWindows (31+) and setAccessibilityDataSensitive (34+) were not exercised below 37. FLAG_SECURE, autofill and the toolbar may also behave differently on older releases or vendor builds.
- The physical-screen appearance of the recents live tile could not be observed on a headless emulator. Only screencap output was seen: black for FLAG_SECURE, full content with only setRecentsScreenshotEnabled(false).
- Not verified: whether Gboard (or another keyboard) actually stops learning when IME_FLAG_NO_PERSONALIZED_LEARNING is present. Only delivery of the flag to the keyboard process was confirmed.
- TalkBack and other accessibility services that declare isAccessibilityTool=true were not tested with isSensitiveData / setAccessibilityDataSensitive. Only a non-tool service (blocked) and UiAutomation (not blocked) were checked.
- TextObfuscationMode.System and the per-input-type 'Show passwords' settings on API 37 were not tested on the device.
- Only my logging autofill service was used. Not tested: Google's own service, third-party password managers, Credential Manager providers, and the Android System Intelligence augmented-autofill service. The augmented service was configured on the image but was never seen to trigger.
- Not tested as a negative case: a filterTextContextMenuComponents placed only at the activity root, to see whether it also covers Dialog fields. Source suggests it cannot (separate layout tree), so the recommended code applies it inside every window.
- android:configChanges, as an alternative way to keep typed input across rotation without saved state, was not tested.
- Not checked: the selection toolbar's system items such as 'Read aloud' (text processing) on selected secret text, and which toolbar items a SecureTextField with selected text offers. Clipboard handling of copied passwords was out of scope.
- Not investigated: why 'adb shell settings put global always_finish_activities 1' had no effect on this build. The Developer options toggle had to be used instead (it calls setAlwaysFinish).
- Environment risk for other labs: the user's Android Studio ran 'adb kill-server' at 12:26, which left emulators offline (emulator-5560, another lab's device, was still offline at the end). If Android Studio stays open it may do this again.
