# PasswordVault: the looks

Decided by the user on 2026-10-08. They were shown three mock-up styles, each on the PIN lock screen, the Passwords list and an account's screen, and answered "keep all". So the app offers all three as a choice, plus the colours of the phone's wallpaper. About the icon and the name they said: "we can replace the icon later, maybe also app name changed". Both are provisional.

`mockups/` holds the mock-ups as they were shown: one self-contained HTML file per screen (`Main.dc.html` is style A's lock screen; `A-`, `B-` and `C-` name the style). Every colour below is taken from them. They are a picture of the intention, not a specification of layout: where a mock-up and ux.md disagree about what a screen contains, ux.md wins.

## What the app offers

Settings › Appearance has two choices:

| Choice | Values | Default |
|---|---|---|
| Colours | Bank blue · Emerald · Night vault · Phone's colours | Bank blue |
| Light or dark | System · Light · Dark (the theme setting that exists) | System |

- **Phone's colours** is Material 3's dynamic colour, which follows the wallpaper. It exists on Android 12 and later only; on older phones the row is not shown, and a stored value of it is read as Bank blue.
- Each of the three own styles has a **light and a dark** colour scheme; "Light or dark" picks between them. The user was promised this. The mock-ups show A and B in light and C in dark; the other three schemes are derived from the same hues.
- The choice is an app setting (not in the vault, not secret), is read before unlock, so the lock screen has it too, and applies at once, without a restart: to every screen, dialog, sheet and the system bars.
- A style is first of all a Material 3 `ColorScheme`. A few details differ between styles beyond colour (below); a screen reads them from the theme and never asks which style is chosen.

## The colours of the mock-ups

Material 3 role names. Roles a mock-up does not show are derived so that every text pair has a contrast of at least 4.5:1 and every icon or outline that carries meaning at least 3:1 (ux.md §8).

| Role | A · Bank blue (light) | B · Emerald (light) | C · Night vault (dark) |
|---|---|---|---|
| primary / onPrimary | `#1D4E89` / `#FFFFFF` | `#006B5E` / `#FFFFFF` | `#F2B84B` / `#2A1C00` |
| primaryContainer / on it | `#D6E3FF` / `#0B2F5E` | `#CDE8E1` / `#00201B` | `#3A2D11` / `#FFDFA3` |
| secondaryContainer / on it | `#DAE2F9` / `#131C2B` | `#CDE8E1` / `#00201B` | `#3A2D11` / `#FFDFA3` |
| background | `#F8F9FF` | `#F4F8F6` | `#0E1216` |
| surface (cards, sheets) | `#F8F9FF` | `#FFFFFF` | `#161C21` |
| surface container (navigation bar) / high (search field) | `#ECEEF5` / `#E6EAF2` | `#FFFFFF` with an outline `#DCE5E1` / `#FFFFFF` with an outline `#BEC9C5` | `#161C21` / `#1C232A` |
| onSurface / onSurfaceVariant | `#191C20` / `#43474E` | `#161D1B` / `#3F4946` | `#E3E6EA` / `#A7AFB8` |
| outline / outlineVariant | `#73777F` / `#C3C7CF` | `#6F7976` / `#BEC9C5` | `#5B636C` / `#333C45` |
| tertiary (the symbols of a shown password; digits use primary) | `#9A4500` | `#8A4B00` | `#8FC7FF` |
| inverseSurface / inverseOnSurface / inversePrimary (the copy status row) | `#2E3138` / `#EEF0F7` / `#A9C7FF` | `#12332D` / `#E6F2EF` / `#8FE0CC` | `#E3E6EA` / `#1A1F24` / `#6A4800` |

The eight avatar tones of the list (ux.md §5.11), background / letter:

| Tone | A (light) | B (light) | C (dark) |
|---|---|---|---|
| blue | `#D6E3FF` / `#0B2F5E` | `#D4E4F7` / `#0E2F4F` | `#1F3450` / `#C3D7F7` |
| rose | `#FFD9DF` / `#5C1129` | `#F9DDE2` / `#5A1A29` | `#46222C` / `#F7BECA` |
| violet | `#E6DEFF` / `#2A1A5E` | `#E3E0F9` / `#2B2559` | `#2F2852` / `#D3C8FF` |
| green | `#D4EBC9` / `#173A08` | `#D2EED4` / `#123A1A` | `#1F3519` / `#BFE0AC` |
| amber | `#FFDDB3` / `#4A2800` | `#F7E3BF` / `#462C00` | `#40300F` / `#F6D08D` |
| teal | `#C9ECE6` / `#003A33` | `#C4EDE5` / `#00382F` | `#143C37` / `#A9E5DA` |
| slate | `#DDE3EA` / `#25313D` | `#DCE4E8` / `#243038` | `#263039` / `#C5D0DB` |
| coral | `#FFDBCF` / `#5A1B06` | `#F9DDD2` / `#55200C` | `#45251B` / `#F7BBA6` |

The schemes the mock-ups do not show (A dark, B dark, C light) keep the hue of each role. C's light scheme cannot use `#F2B84B` as primary on a light surface (contrast under 2:1): its primary is a deep gold such as `#6A4800`, and the bright gold becomes a container colour.

Where the built schemes (`APP/ui/theme/Schemes.kt`, build plan 4.3b) differ from the table above, and why:
- **`surface` is the screen's colour** (the table's "background") in every style, because Material 3 draws the top bar and the list's rows on `surface`. The table's "surface (cards, sheets)" colour is `surfaceContainerLow` and `surfaceContainerLowest`.
- **Emerald's two outlines** (`#DCE5E1` around the navigation bar, `#BEC9C5` around the search field) are not a role: the look hands them out as `Look.barOutline` and `Look.searchOutline`, for the second step to draw.
- **Night vault's dark outline is `#757D86`**, not the mock-up's `#5B636C`, which has only 2.8:1 on `#161C21` (a sheet) and 2.6:1 on `#1C232A` (a dialog), under the 3:1 that an outline that carries meaning needs (ux.md §8).

## What differs between styles besides colour

| Detail | A · Bank blue | B · Emerald | C · Night vault | Phone's colours |
|---|---|---|---|---|
| PIN keys | filled circles | no fill; the fingerprint key tonal | outlined circles | filled circles |
| Avatars in the list | circle | rounded square (radius 12 of 40 dp) | circle | circle |
| Lock screen | the icon, the name, the subtitle and the dots sit on a panel in the primary colour with rounded lower corners | plain | plain | plain |
| List and account screen | rows on the background | rows grouped on cards (radius 20 dp) on the tinted background | rows on the background; the password block of the account screen on a card | rows on the background |
| "Add" button | tonal (primary container) | filled (primary), fully rounded | filled (primary) | tonal |

Colours, the avatar tones and shape and the PIN keys come first. The lock screen's panel and the cards are the second step, because they change the layout of screens that other packages own (build-plan.md §12).

## The icon and the name

- **Icon, provisional.** The shield with a keyhole of mock-up A, white on `#1D4E89`: an adaptive icon with a monochrome layer, and bitmaps for Android 7. It has one source file and one script that makes every size, so that replacing it later is one step: [icon/icon.svg](icon/icon.svg), the one drawing, and [icon/make-icon.py](icon/make-icon.py), which writes every file of the launcher icon and `ic_app_logo.xml`, records each file's hash in `icon/generated.sha256` and with `--check` says whether the files are still those it made (a test fails on a file edited by hand). The same drawing, as a vector, is the app's logo on the Welcome and lock screens: `AppLogo` (`ui/common`) draws it on a tile with rounded corners (28 per cent of its side) in the style's `primary`, the shield in `onPrimary` and 5/8 of the tile, as a launcher shows the icon; 72 dp on Welcome, 64 dp on the PIN pad and on the fingerprint-only screen. The master-password screen has none (ux.md §5.10). It replaced the key in a circle that stood in for it while the launcher icon was no vector.
- **Name.** "PasswordVault" for now, spelled one way in every language (architecture.md D-23) and written in one resource, `app_name`; no other text repeats it, so a new name is one change. The application ID `io.github.dilshodbekmx.passwordvault` never changes, whatever the name becomes: Android keys the app's data to it.
