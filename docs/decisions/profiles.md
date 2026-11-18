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

## Who's watching

- With more than one profile, the app opens on "Who's watching?" (Settings: "Ask who's watching at start", on by default, as most households with profiles share a device). With one profile nothing changes: no picker and no profile button.
- Home shows the profile's badge beside Settings while there is more than one profile; tapping it switches. Settings › Profiles adds and manages them.
