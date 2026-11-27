// Every word a person reads on the TV, in the TV's own language (docs/decisions/languages.md): the same 11 translations
// as the Android app, picked by the TV's language with no picker, and English when Tuned has no translation for it.
import { isRightToLeft, pickLanguage, type Language } from "@tuned/shared/i18n";
import { ar } from "./strings/ar.ts";
import { de } from "./strings/de.ts";
import { en, type Strings } from "./strings/en.ts";
import { es } from "./strings/es.ts";
import { fr } from "./strings/fr.ts";
import { hi } from "./strings/hi.ts";
import { it } from "./strings/it.ts";
import { pl } from "./strings/pl.ts";
import { pt } from "./strings/pt.ts";
import { ru } from "./strings/ru.ts";
import { tr } from "./strings/tr.ts";
import { zh } from "./strings/zh.ts";

export type { Strings };

export const TRANSLATIONS: Record<Language, Strings> = { en, es, pt, fr, de, it, tr, ru, pl, ar, hi, zh };

/** The TV's languages, most wanted first: Tizen gives the TV's menu language to the web app as the browser's. */
function deviceLanguages(): string[] {
    if (typeof navigator === "undefined") return [];
    return navigator.languages?.length ? [...navigator.languages] : [navigator.language];
}

export const language: Language = pickLanguage(deviceLanguages());
export const rightToLeft = isRightToLeft(language);
export const t: Strings = TRANSLATIONS[language];

export function ago(at: number, now = Date.now()): string {
    const min = Math.floor((now - at) / 60_000);
    if (min < 1) return t.justNow;
    if (min < 60) return t.minutesAgo(min);
    return t.hoursAgo(Math.floor(min / 60));
}

export function clock(ms: number): string {
    const s = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    const two = (n: number) => String(n).padStart(2, "0");
    return h ? `${h}:${two(m)}:${two(sec)}` : `${m}:${two(sec)}`;
}

export function timeOfDay(ms: number): string {
    return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

export function bytes(n: number): string {
    if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
