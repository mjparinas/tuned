# Branding

**Name: Tuned** (Melvin, 2026-11-17).

**Mark.** A rounded play button sending out two signal arcs, as in "tuned in". White play button and signal-teal arcs on a Tuned violet gradient. The adaptive icon has background, foreground and monochrome layers (the monochrome layer gives the Android 13+ themed icon). The status bar icon is the same mark in white. The splash screen shows the mark on the theme's background, and "Who's watching?" at start shows the mark and the name in its upper left ([profiles](profiles.md)).

**Colours.**

| Token  | Hex       | Use                                 |
| ------ | --------- | ----------------------------------- |
| Violet | `#6D4AFF` | Brand, icon, primary in light theme |
| Deep   | `#3B1FB8` | Icon gradient end                   |
| Teal   | `#00C2A8` | Signal arcs, secondary accents      |
| Ink    | `#121018` | Dark background                     |

**Themes.** Light, dark and system (default), with two options in Settings › Appearance: _Wallpaper colours_ (Material You dynamic colour, Android 12+, off by default so the brand shows) and _Pure black_ (true black in dark mode for OLED screens, so the video is the only light in the room).

## Contrast in every theme (Melvin, 2026-11-19)

"Do 6", from the Horizon Maps review: status and accent colours deep enough to read on light backgrounds. Tuned already draws them from the theme's own light and dark tones rather than fixed colours, so one fix was needed: the Blue accent's light tone was 4.4:1 on the darkest card colour and is now `#1B57C4` (5:1). `ThemeContrastTest` now checks every accent in light, dark and pure black: primary, secondary, tertiary, error and the body text colours at 4.5:1 or better on every background and card colour, and the words on filled buttons and containers. A new accent or colour that falls short fails CI. Settings' badge and What's new colours have their own deep light and soft dark shades for the same reason ([settings](settings.md)).

## Dialogs and the accent row fit the screen (Melvin, 2026-11-19)

"These pop ups shouldn't take the full width." Every pop-up is `TunedDialog` (`ui/common/TunedDialog.kt`), Material 3's alert dialog with at least 24 dp between the card and each edge of the screen, no wider than 560 dp, and centred, on a phone, tablet or TV. Material's own dialog fills whatever width its window gives it, and where that window is the whole screen (some devices, and the JVM screenshots) a long message stretched it edge to edge with its rounded corners cut off. detekt's `ForbiddenImport` rule stops Material's `AlertDialog` being used directly, so a new dialog gets the margins too. The player's few platform dialogs (`android.app.AlertDialog`) already have the system's margins.

"The red is cut off on the right, either remove a colour, or push them closer together." All seven accent colours stay; the swatches in Settings › Appearance share the row's width (up to 40 dp each, 8 dp apart) instead of scrolling sideways, so the last one is never cut off on a narrow phone.
