# Languages

**What** (Melvin, 2026-11-20: "can you add multiple languages please, do the top 10 most popular languages", then "add polish"). The Android app, on phones, tablets and TVs, is translated into 11 languages besides English: Spanish, Portuguese (Brazilian wording), French, German, Italian, Turkish, Russian, Polish, Arabic, Hindi and Simplified Chinese. The website is not translated.

**The device picks the language** (Melvin, 2026-11-20: "it should auto choose based on the device's locale"). There is no language picker in Tuned. Android shows Tuned in the device's language, and in English when Tuned has no translation for it. `res/xml/locales_config.xml` lists the languages so Android 13 and later can also offer a per-app language in Android's own settings (Settings › Apps › Tuned › Language); `LanguagesTest` keeps that list and the `values-xx` folders the same.

**What stays as it is**:

- The name **Tuned** is never translated, and neither are other names: Premium, Trakt, Simkl, OpenSubtitles, TMDB, Google Play, Xtream Codes, the Tuned website's own "Pair a TV".
- Strings that are only a format (`%1$s · %2$s`, `%1$s/10`) or a technical label (4K, FHD, HD, SD, 5.1, REC, FPS) are `translatable="false"` in `values/`, so every language uses them.
- What comes from the provider or TMDB (channel, group, film and episode names, the guide) is shown as the provider sent it.
- A raw technical error a server returns (such as "HTTP 401") inside a message stays as it came.

**Countries and languages follow the reader** (2026-11-20). In Live TV's filters and Settings › Channels and lists › Countries and languages, a country or language tag shows its name in the device's language (`Regions.displayName(tag, locale)` in `:core`, from Java's own locale names). Tuned's own areas (Africa, Asia, Europe, Nordic, Latino, ex-Yugoslavia) come from `strings_region_names.xml`, and a tag Java doesn't know keeps its English name.

**Right to left** (2026-11-20). Arabic draws the whole app mirrored: the manifest has `supportsRtl`, layouts use start and end, arrows and back icons are the `AutoMirrored` set, and the guide grid, the player and the TV screens were reviewed for it, with Arabic screenshots on a phone and a TV. Times, channel numbers and video stay left to right.

**Keeping them up to date**. A new string in `values/` needs its translation in all 11 `values-xx` folders in the same pull request; Android Lint's `MissingTranslation` fails CI otherwise. Plurals give every quantity the language uses (Russian and Polish one, few, many and other; Arabic zero, one, two, few, many and other; Chinese other only), or Lint's `MissingQuantity` fails. A string that must never change between languages is marked `translatable="false"` instead. The `Screenshots` set draws Live TV and Settings in Arabic, German, Hindi and Chinese (`LanguageScreenshots`), so long words and mirrored layouts can be checked without a device.
