# Accessibility

**What** (Melvin, 2026-11-18: "can you audit the app for any accessibility issues"). Tuned works with TalkBack, Switch Access, a keyboard or remote, large text and Android's "Time to take action" setting, without adding anything to the screen for people who don't use them. "Get out of the way" holds here too: the fixes are what a screen reader hears and how big a target is, not new buttons or pop-ups.

**The player's controls stay up for a screen reader** (2026-11-18, `player/AccessibleControls.kt`, with a test). The controls hide after the time set in Settings › Playback (3 seconds by default). With TalkBack on, that hid them before a swipe could reach the second button, so while a screen reader is on they stay until the video is tapped again. Without one, Android's "Time to take action" setting can lengthen the chosen time, never shorten it. The playing channel in the mini guide says "Playing" as well as being coloured.

**Rules for every screen** (2026-11-18):

- Every icon that is the only thing on a button has a label in `strings.xml`. An icon beside text that says the same thing has none (`contentDescription = null`), so it isn't read twice. Labels that repeat down a list name their row ("Guide for BBC One", not "Guide" a hundred times).
- A state shown only by colour or a mark (the programme on now, the playing channel, the current profile, a favourite, a picked swatch) is also given in words: a `stateDescription`, a label, or `selectable` with a role.
- On and off settings use `toggleable` with `Role.Switch`, so TalkBack says "on" or "off"; choices from a set use `selectable` with `Role.RadioButton`. Section titles are headings (`SectionHeader`), so a screen reader can jump between them.
- What a tap or long press does is named when it isn't obvious (`onClickLabel`, `onLongClickLabel`: "Play", "Details", "More options", "Edit").
- Something small inside something else that can be tapped (the hide eye inside a group chip) is also a custom accessibility action on the outer thing.
- Anything tappable is at least 48dp to a finger, with `minimumInteractiveComponentSize()` when it draws smaller.
- Text is in `sp` and grows with the system font size. A field or button that holds text has a minimum height (`heightIn(min = …)`), never a fixed one, so large text isn't cut off.
- Colours come from the theme (Material 3 roles meet contrast in light and dark). Captions keep the FCC palette, and each colour has a name.

The semantics of shared Settings widgets are checked by `SettingsSemanticsTest` (Compose UI test on Robolectric).

**Known gaps, not fixed yet** (2026-11-18 audit): the grid guide's rows are a fixed 64dp, so a programme's time can be cut off at the largest font sizes; brightness and volume are drag gestures only in the player (the device's own volume keys and brightness still work).
