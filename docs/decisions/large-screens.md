# Large screens

**What** (Melvin, 2026-11-17: yes to "proper tablet layouts"). Tablets, unfolded foldables and Chromebooks get layouts that use the room instead of a stretched phone screen. The rules are in `core/ScreenLayout.kt`, with tests, and follow Material's window size classes by width: compact under 600 dp, medium to 840 dp, expanded above.

- **Navigation rail.** From medium width up, Live TV, Movies, Shows and Downloads run down the left edge (`ui/browse/HomeLayout.kt`) instead of along the bottom, which leaves more height for the list and puts the tabs under a thumb holding a tablet. Settings sits on the rail under Downloads, on tablets and TV alike (Melvin, 2026-11-19: "tablets and tv should show settings on the left side under downloads"), and so leaves the menu beside search there; phones keep it in that menu. A kids profile has no Settings item, as before.
- **Bigger posters.** Off phones the grids' minimum poster width goes from 112 to 140 dp, so a tablet shows bigger posters as well as more of them.
- **List and detail side by side.** On expanded screens, tapping a film, a show or a channel's guide opens it in a pane on the right (45 % of the width) while the list stays where it was; back or the pane's arrow closes it. These are the same screens a phone opens full screen, each with view models keyed by what they show, so nothing is duplicated. If the window narrows with a pane open (folding, rotating, split screen), the page carries on full screen.
- **Guide beside the player.** On a tablet in landscape (expanded width and at least 600 dp tall, so a phone on its side doesn't count) the player's channel guide docks beside the video, which shrinks to make room, instead of covering it. It is placed again on rotation, since the player handles configuration changes itself.

Width comes from `LocalWindowInfo.containerSize` in Compose (it follows split screen and folding) and from `Configuration.screenWidthDp` in the player. No setting: these are the platform's conventions, and a phone looks exactly as before.

Not yet: a two-pane Settings screen, and a dedicated TV layout (see [TV](tv.md)).
