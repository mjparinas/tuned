# Profiles

Melvin (2026-11-17) asked for "profiles and pin lock". The parental PIN for adult categories already existed ([security](security.md)); profiles are new.

## What a profile keeps

Each profile has its own **favourites, watch history (Continue Watching and watched marks), watch later list and seen episodes** ("new episodes"). Everything else is shared: the playlist and guide, settings, downloads, channel health and the saved logins. One household shares one provider account, so splitting the playlist or settings would only add work.

- The profiles live in Room (`profiles`), and the one in use is a single row in `current_profile`. Every list query reads that row in SQL, so a switch refreshes every list, pager and Continue Watching row at once with no code passing a profile id around. Writes go through DAO methods that stamp the profile in use.
- The profile in use is mirrored into DataStore (`profile_id`, `kids_profile`, `profile_count`) so the first frame knows it without opening the database. `ProfileRepository` writes both and puts the mirror right at start. The mirror is never backed up.
- Upgrading (database version 13) gives everything already saved to the first profile, named "Me" until renamed.

## Kids profiles

- Only kids and family categories show: groups with kid, child, cartoon, family, junior, disney, nick, baby, animation or boomerang in the name, plus live channels whose own name has one of those words (except family and animation, which match grown-up channels such as "Family Guy 24/7"). `core/KidsContent` holds the rule; the SQL in `Daos.kt` applies it, and a test checks the two agree.
- Adult categories never show, even with the parental PIN.
- There's no Settings button on Home, and profiles can't be added, changed or removed from a kids profile.
- **Leaving a kids profile asks for the parental PIN** (or the target profile's own PIN), including from "Who's watching?" at start. So a kids profile can't be made without a parental PIN: saving the first one asks to set it.

## PIN locks

- Any profile can be locked with its own PIN of 4 to 8 digits, stored as a `PinHash` like the parental PIN. Switching to it, or editing it from another profile, asks for it.
- "Forgot PIN" takes the account password (or playlist link) instead.
- One throttle (`PinThrottle`) covers every PIN on the device: five wrong in a row wait 30 seconds.
- Switching profile locks adult categories again, since an unlock belongs to the person who typed the PIN.
- **The player enforces the same rules as the lists** (security audit, 2026-11-18). The quick-settings tile, the favourites widget, Android TV's home screen cards and other apps' play intents open the player without going through a list, so the player itself (`PlayGate`) refuses an adult channel while adult categories are locked, and anything outside kids and family on a kids profile, with a short message. The widget and TV cards leave those channels out too. Before this, the tile reopened an adult channel after the unlock had ended.

## Who's watching

- **Pictures** (Melvin, 2026-11-18: "make this page look more visually appealing … allow people to choose an avatar … special avatars for pro users only"). A profile wears an avatar, an emoji on a soft gradient, or its first letter on its colour as before. Emoji cost nothing to download, stay sharp at every size, and are from Unicode 10 or older so Android 8 draws them. The set is in `core/Avatars.kt`; the stored key (`profiles.avatar`, database version 17) never changes, and an unknown key falls back to the letter.
- Twelve are free (fox, panda, tiger and friends; they take the profile's colour). Twelve are **Premium** (dragon, unicorn, T. rex, rocket, wizard, crown and more), each with its own gradient and a slowly turning gold, rose and cyan ring on the picker, still on Home's small badge. Without Premium they show a lock and open the Premium screen. A profile keeps a Premium picture it already has if Premium ends, since taking it away would only annoy.
- The picker is centred on a soft glow of the accent colour, with large rounded-square pictures (104dp), a ring around the profile in use, a Kids pill under kids profiles, a press and focus bounce, and "Manage profiles" as a button under the profiles instead of a pencil in the corner. At start the upper left carries the Tuned mark and name (Melvin, 2026-11-18: "add the tuned logo and name on the upper left side"); opened from Home or Settings it has a back arrow instead.
- **At most five profiles** (Melvin, 2026-11-18: "there should be [a limit] otherwise the ui will be cluttered"; five as Netflix does). "Add profile" disappears at five (`ProfileRules.MAX_PROFILES`). A device that somehow has more keeps them all.
- With more than one profile, the app opens on "Who's watching?" (Settings: "Ask who's watching at start", on by default, as most households with profiles share a device). With one profile nothing changes: no picker and no profile button.
- Home shows the profile's badge beside Settings while there is more than one profile; tapping it switches. Settings › Profiles adds and manages them.
