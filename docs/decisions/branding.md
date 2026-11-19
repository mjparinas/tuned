# Branding

**Name: Tuned** (Melvin, 2026-11-17).

**Mark: the channel stack** (Melvin, 2026-11-19: "swap everything over to the channel stack", picked from five drawn directions). Three rounded play buttons fanned like flipping channels: violet at the back, signal teal in the middle, white in front, on Tuned ink (a near-black gradient, `#26213A` to `#0B0A10`) so the icon sits well beside video. A thin gap separates each button so they stay apart at small sizes. It replaced the earlier mark, a play button sending out two signal arcs on violet. The adaptive icon has background, foreground and monochrome layers. The monochrome layer (the Android 13+ themed icon) keeps the gaps and draws the back two buttons at 50% and 75% opacity, so the three still read as three in one colour. The status bar icon is the same as the themed icon. The splash screen shows the mark in an ink circle on the theme's background, the Android TV banner shows the mark and the name on ink, and "Who's watching?" and the welcome screen show the icon ([profiles](profiles.md)). The mark has no letters, so it stays if the app is renamed; only the name in the TV banner would change. `docs/branding/` holds the SVG, a 512 px PNG and a preview.

**Colours.**

| Token  | Hex       | Use                                             |
| ------ | --------- | ----------------------------------------------- |
| Violet | `#6D4AFF` | Brand, back play button, primary in light theme |
| Deep   | `#3B1FB8` | Deep violet accents                             |
| Teal   | `#00C2A8` | Middle play button, secondary accents           |
| Ink    | `#121018` | Dark background; icon background `#1A1726`      |

**Themes.** Light, dark and system (default), with two options in Settings › Appearance: _Wallpaper colours_ (Material You dynamic colour, Android 12+, off by default so the brand shows) and _Pure black_ (true black in dark mode for OLED screens, so the video is the only light in the room).

## Contrast in every theme (Melvin, 2026-11-19)

"Do 6", from the Horizon Maps review: status and accent colours deep enough to read on light backgrounds. Tuned already draws them from the theme's own light and dark tones rather than fixed colours, so one fix was needed: the Blue accent's light tone was 4.4:1 on the darkest card colour and is now `#1B57C4` (5:1). `ThemeContrastTest` now checks every accent in light, dark and pure black: primary, secondary, tertiary, error and the body text colours at 4.5:1 or better on every background and card colour, and the words on filled buttons and containers. A new accent or colour that falls short fails CI. Settings' badge and What's new colours have their own deep light and soft dark shades for the same reason ([settings](settings.md)).

## Dialogs and the accent row fit the screen (Melvin, 2026-11-19)

"These pop ups shouldn't take the full width." Every pop-up is `TunedDialog` (`ui/common/TunedDialog.kt`), Material 3's alert dialog with at least 24 dp between the card and each edge of the screen, no wider than 560 dp, and centred, on a phone, tablet or TV. Material's own dialog fills whatever width its window gives it, and where that window is the whole screen (some devices, and the JVM screenshots) a long message stretched it edge to edge with its rounded corners cut off. detekt's `ForbiddenImport` rule stops Material's `AlertDialog` being used directly, so a new dialog gets the margins too. The player's few platform dialogs (`android.app.AlertDialog`) already have the system's margins.

"The red is cut off on the right, either remove a colour, or push them closer together." All seven accent colours stay; the swatches in Settings › Appearance share the row's width (up to 40 dp each, 8 dp apart) instead of scrolling sideways, so the last one is never cut off on a narrow phone.

## The website (Melvin, 2026-11-19)

"Can you style the website similar to the app, add the logo, colours, etc." The public pages of the server's website (`/account`, `/tv`, the order-number page at `/` and `/app/signed-in`) share one stylesheet, `devices/public/site.css`, built from the app's own theme (`ui/theme/Theme.kt`): its Material 3 light and dark colours, picked by the device's setting as the app's System theme does; cards rounded 20, text fields 14 and buttons fully round, as `TunedShapes` sets; semibold titles; and a soft violet glow behind the top of the page. Every page opens with the channel stack on ink and the name, linking to the account page, and the card that sells Premium or seats has the mark's violet to teal edge. The favicon, Apple touch icon and SVG icon are the same mark (`favicon.ico`, `apple-touch-icon.png`, `icon.svg`, from `docs/branding/`), and the browser's bar takes the page's background colour. Fonts are the device's own (Roboto on Android, like the app), so nothing loads from another site and the content security policy stays `'self'`. The admin keeps its own look ([admin](admin.md)).
