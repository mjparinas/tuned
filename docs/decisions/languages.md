# Languages

**What** (Melvin, 2026-11-20: "can you add multiple languages please, do the top 10 most popular languages", then "add polish"). The Android app, on phones, tablets and TVs, is translated into 11 languages besides English: Spanish, Portuguese (Brazilian wording), French, German, Italian, Turkish, Russian, Polish, Arabic, Hindi and Simplified Chinese. The website is not translated. The Samsung TV and Vega OS apps are, in the same languages ([below](#the-tv-apps-melvin-2026-11-27)).

**The device picks the language** (Melvin, 2026-11-20: "it should auto choose based on the device's locale"). There is no language picker in Tuned. Android shows Tuned in the device's language, and in English when Tuned has no translation for it. `res/xml/locales_config.xml` lists the languages so Android 13 and later can also offer a per-app language in Android's own settings (Settings › Apps › Tuned › Language); `LanguagesTest` keeps that list and the `values-xx` folders the same.

**What stays as it is**:

- The name **Tuned** is never translated, and neither are other names: Premium, Trakt, Simkl, OpenSubtitles, TMDB, Google Play, Xtream Codes, the Tuned website's own "Pair a TV".
- Strings that are only a format (`%1$s · %2$s`, `%1$s/10`) or a technical label (4K, FHD, HD, SD, 5.1, REC, FPS) are `translatable="false"` in `values/`, so every language uses them.
- What comes from the provider or TMDB (channel, group, film and episode names, the guide) is shown as the provider sent it.
- A raw technical error a server returns (such as "HTTP 401") inside a message stays as it came.

**Countries and languages follow the reader** (2026-11-20). In Live TV's filters and Settings › Channels and lists › Countries and languages, a country or language tag shows its name in the device's language (`Regions.displayName(tag, locale)` in `:core`, from Java's own locale names). Tuned's own areas (Africa, Asia, Europe, Nordic, Latino, ex-Yugoslavia) come from `strings_region_names.xml`, and a tag Java doesn't know keeps its English name.

**Right to left** (2026-11-20). Arabic draws the whole app mirrored: the manifest has `supportsRtl`, layouts use start and end, arrows and back icons are the `AutoMirrored` set, and the guide grid, the player and the TV screens were reviewed for it, with Arabic screenshots on a phone and a TV. Times, channel numbers and video stay left to right.

**Keeping them up to date**. A new string in `values/` needs its translation in all 11 `values-xx` folders in the same pull request; Android Lint's `MissingTranslation` fails CI otherwise. Plurals give every quantity the language uses (Russian and Polish one, few, many and other; Arabic zero, one, two, few, many and other; Chinese other only), or Lint's `MissingQuantity` fails. A string that must never change between languages is marked `translatable="false"` instead. The `Screenshots` set draws Live TV and Settings in Arabic, German, Hindi and Chinese (`LanguageScreenshots`), so long words and mirrored layouts can be checked without a device.

## The TV apps (Melvin, 2026-11-27)

Melvin, 2026-11-27: "translate both apps into the 10 languages". The Samsung TV app (`tv/tizen`) and the Vega OS app (`tv/vega`) speak the same 11 languages besides English as the Android app, with the Android app's wording wherever a line is the same ("Tuned-Konto", "TV en directo", "Live-TV"), and "Movies", never "Films", in English.

- **The TV picks the language**, as on Android, with no picker. `@tuned/shared/i18n` (`pickLanguage`) takes the TV's languages, most wanted first (the browser's languages on a Samsung TV, React Native's `I18nManager` locale on Vega), and picks the first Tuned has. Portuguese from Portugal gets the Brazilian wording and Chinese only its simplified script, as Android's `values-pt` and `values-zh-rCN` do; anything else is English.
- **One file per language** beside each app's English (`src/strings/en.ts` on Samsung, `src/ui/strings/en.ts` on Vega), with the same keys. A line that takes a number is a function, and the languages with more than one plural form (Russian, Polish, Arabic) use `plural` from `@tuned/shared/i18n`, Unicode's rules written out because Vega's Hermes has no `Intl.PluralRules`. Each app's tests fail when a language lacks a line English has (`missingKeys`), as Lint's `MissingTranslation` does on Android, so **a new or changed line gets its translation in every language in the same pull request**.
- **Arabic is right to left**: the Samsung app sets `dir="rtl"` on the page and its styles use start and end, so the rail, rows and remote all mirror; on Vega, React Native's own mirroring follows the stick's system language (the browser preview draws it mirrored; not yet checked on a real stick).
- Names stay as they are, as on Android: Tuned, Premium, Xtream, M3U, XMLTV, tunedplayer.com, and whatever the provider sends (channel, group, film and show names, the guide).
