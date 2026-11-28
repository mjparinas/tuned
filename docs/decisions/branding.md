# Branding

**Name: Tuned** (Melvin, 2026-11-17).

**Mark: the channel stack** (Melvin, 2026-11-19: "swap everything over to the channel stack", picked from five drawn directions). Three rounded play buttons fanned like flipping channels: violet at the back, signal teal in the middle, white in front, on Tuned ink (a near-black gradient, `#26213A` to `#0B0A10`) so the icon sits well beside video. A thin gap separates each button so they stay apart at small sizes. It replaced the earlier mark, a play button sending out two signal arcs on violet. The adaptive icon has background, foreground and monochrome layers. The monochrome layer (the Android 13+ themed icon) keeps the gaps and draws the back two buttons at 50% and 75% opacity, so the three still read as three in one colour. The status bar icon is the same as the themed icon. The splash screen shows the mark on plain Tuned ink (`tuned_ink`, `#1A1726`) in light and dark mode, on phones and TVs and on every Android version (Melvin, 2026-11-20: on the light background "you can't see the first white arrow"); it used to be an ink circle on the theme's near-white background, and not every Android version and launcher draws that circle, which left the white button on white. `SplashThemeTest` checks the splash background against white, the Android TV banner shows the mark and the name on ink, and "Who's watching?" and the welcome screen show the icon ([profiles](profiles.md)). The mark has no letters, so it stays if the app is renamed; only the name in the TV banner would change. `docs/branding/` holds the SVG, a 512 px PNG and a preview.

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

### Sign-in codes stay on one line (Melvin, 2026-11-20)

"Can you make the pop-up wider? I don't ever want to see the code text wrapped to the next line." On his TV, Restore from your Tuned account showed "ECUZ-KH4X" broken after the dash and the address broken after "www.tunedplayer.". So:

- **A TV's sign-in code dialog is wider**: the Tuned account dialog (Restore from your Tuned account on the welcome screen, and Settings › Tuned account) asks `TunedDialog` for `maxWidth = DialogWideMaxWidth` (720 dp) on a TV, which draws the same Material card without Material's own 560 dp cap. It still keeps 24 dp off each edge and sits in the centre; on a phone it is the usual dialog.
- **A code never wraps** (`OneLineCode`, `ui/common/OneLineCode.kt`): the Tuned account, Trakt and Simkl codes and the TV's Sign in with your phone code are one line at full size when there is room, and shrink a step at a time (down to 12 sp) when there isn't, in any language, at any text size, on a phone or a TV. Any new code shown to be typed elsewhere uses it.
- **An address isn't split mid-word**: the web address in those steps is passed through `unbreakable`, which puts a word joiner between its characters, so the whole address moves to the next line rather than breaking after a dot or slash.

"The red is cut off on the right, either remove a colour, or push them closer together." All seven accent colours stay; the swatches in Settings › Appearance share the row's width (up to 40 dp each, 8 dp apart) instead of scrolling sideways, so the last one is never cut off on a narrow phone.

## Moving between screens is one fade (Melvin, 2026-11-28)

"When I click on the navigation buttons on the bottom nav, pages change and fade between them. When I go to settings and click the android back button, the page shrinks and then goes away. How do I keep that fade effect on back buttons." Every move between the app's screens is the same cross-fade: opening one (Settings from the bottom bar included), Back, and Android's back gesture. Navigation Compose already faded forward and on a plain Back, but its default for the predictive back gesture (Android 14 and later, which the app opts into with `enableOnBackInvokedCallback`) shrank the old screen to 70% as it left. `ScreenFade` (`ui/ScreenFade.kt`) gives the `NavHost` Navigation's own 700 ms fade for all six of its transitions, so a new screen added to a feature's navigation file fades the same way with nothing to set. It uses only Compose animation, so Android 8 to 13 behave as before; the gesture still follows the finger, as a fade rather than a shrink.

## The website (Melvin, 2026-11-19)

"Can you style the website similar to the app, add the logo, colours, etc." The public pages of the server's website (`/account`, `/tv`, the order-number page at `/` and `/app/signed-in`) share one stylesheet, `devices/public/site.css`, built from the app's own theme (`ui/theme/Theme.kt`): its Material 3 light and dark colours, picked by the device's setting as the app's System theme does; cards rounded 20, text fields 14 and buttons fully round, as `TunedShapes` sets; semibold titles; and a soft violet glow behind the top of the page. Every page opens with the channel stack on ink and the name, linking to the account page, and the card that sells Premium or seats has the mark's violet to teal edge. The favicon, Apple touch icon and SVG icon are the same mark (`favicon.ico`, `apple-touch-icon.png`, `icon.svg`, from `docs/branding/`), and the browser's bar takes the page's background colour. Fonts are the device's own (Roboto on Android, like the app), so nothing loads from another site and the content security policy stays `'self'`. The admin keeps its own look ([admin](admin.md)).
