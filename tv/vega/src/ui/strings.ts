// Every word a person reads on the stick, in the stick's own language (docs/decisions/languages.md): the same 11
// translations as the Android app, picked by the TV's language with no picker, and English when Tuned has none for it.
// `t` starts in English and main.tsx switches it once, before the first screen draws.
import { isRightToLeft, pickLanguage, type Language } from "@tuned/shared/i18n";
import { ar } from "./strings/ar";
import { de } from "./strings/de";
import { en, type Strings } from "./strings/en";
import { es } from "./strings/es";
import { fr } from "./strings/fr";
import { hi } from "./strings/hi";
import { it } from "./strings/it";
import { pl } from "./strings/pl";
import { pt } from "./strings/pt";
import { ru } from "./strings/ru";
import { tr } from "./strings/tr";
import { zh } from "./strings/zh";

export type { Strings };

export const TRANSLATIONS: Record<Language, Strings> = { en, es, pt, fr, de, it, tr, ru, pl, ar, hi, zh };

export const t: Strings = { ...en };
export let language: Language = "en";
let tag = "en-CA";

/** Picks the first of the TV's languages Tuned has (English otherwise) and switches every line to it. */
export function chooseLanguage(tags: readonly (string | null | undefined)[]): Language {
    language = pickLanguage(tags);
    tag = tags.find((each): each is string => !!each)?.replace(/_/g, "-") ?? "en-CA";
    Object.assign(t, TRANSLATIONS[language]);
    return language;
}

export const rightToLeft = () => isRightToLeft(language);

/** The TV's own language tag ("de-DE"), for times of day. */
export const locale = () => tag;
