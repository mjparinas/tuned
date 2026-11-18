# Android TV

**What** (backlog, 2026-11-17: "Android TV remote support"). The same APK installs on Android TV and Google TV, shows a banner on the TV home screen, and works with a remote alone.

**One app, not a second one.** The phone screens already work with a remote: Compose moves focus between buttons, cards and rows with the arrows, and centre or OK clicks (a long press opens the same menu as a long touch). A separate Compose for TV interface (`androidx.tv`) would mean a second copy of every screen to keep in step, so instead:

- Everything a remote can land on (posters, channel rows, Continue Watching cards, search results, episodes, guide rows) shows a clear outline in the accent colour while focused (`ui/common/FocusRing.kt`). Touch never focuses these, so phones look the same.
- The manifest says touch, Wi-Fi and leanback are optional, adds the TV launcher category, and gives the app a 320 × 180 banner (`drawable/tv_banner.xml`, a vector, so it stays sharp at 4K).

A dedicated TV layout can come later if the phone layout proves awkward from the sofa.

**The remote in the player** (`core/RemoteKeys.kt`, with tests). Media3's controls already handle play, pause, fast forward and rewind, and any arrow shows the controls. On top of that:

- On live TV, up and down change channel while the controls are hidden, as on a cable box. The channel up and down buttons (and page up and down) change channel at any time.
- In films and episodes, left and right skip back and forward by the player's skip amounts while the controls are hidden.
- The guide (or menu) button opens and closes the mini guide on live TV; the info button shows stream info.
- Once the controls show, the arrows move between buttons as usual. While the screen is locked, keys do nothing but reveal the unlock button through back.

Holding an arrow keeps skipping; holding a channel button changes channel once, so a long press can't flip through several channels and open several connections in a row.

**Channel numbers and flip back** (2026-11-17, from the app audit). Live channels are numbered 1 up in playlist order (`items.number`). Each playlist refresh sets the numbers, and an older cache gets them once in the background (`ChannelNumbers`). The number shows beside each channel in Live TV (Settings › Channel numbers, on by default). In the player, typing a number on the remote changes to that channel 1.5 seconds after the last digit (`core/RemoteKeys`, `player/ChannelDial`). The last-channel button, or the flip-back button in the player's toolbar on phones, goes back to the channel watched before. Both change the stream the player already has, so they never open a second connection. Numbers follow the playlist rather than the provider's `tvg-chno`, which few playlists set and which can repeat.

**Start on the last channel** (2026-11-17, from the app audit). Settings › Start on the last channel (off by default) makes a fresh launch from the home screen or the TV launcher open the player on the live channel watched last, as a cable box does. The lists load from the cache underneath, and back shows them. A search from the Assistant, a widget or a link opens what it asked for instead. When "Who's watching?" opens at start (more than one profile, asked at every start), the app waits for the pick instead (2026-11-18): the last channel belonged to whoever watched last, so a kids profile could start on a grown-up's channel past its PIN.

**Home screen** (from the app audit, 2026-11-17). On Android TV, films and episodes in progress appear in the system's Continue watching row (Watch Next), and favourite channels in a Tuned row of their own (`work/TvHome.kt`, `androidx.tvprovider`). They are brought up to date a few seconds after the app opens, after playing and after a favourite changes, and removed on sign-out or a playlist switch. A card opens `MainActivity` with `ACTION_PLAY`, which starts the player with the lists underneath. Nothing runs on phones.
