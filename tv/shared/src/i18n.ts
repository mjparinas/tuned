// The TV apps' languages (docs/decisions/languages.md): the same 11 translations as the Android app, picked by the TV's
// own language with no picker, and English when Tuned has no translation for it. Each app keeps its words beside its
// screens; this file only picks the language and the plural form, so both apps pick them the same way.

/** English first, then the Android app's `values-xx` folders, in the same order as docs/decisions/languages.md. */
export const LANGUAGES = ["en", "es", "pt", "fr", "de", "it", "tr", "ru", "pl", "ar", "hi", "zh"] as const;
export type Language = (typeof LANGUAGES)[number];

/**
 * The first of the TV's languages Tuned has, as Android picks a `values-xx` folder: "pt-PT" gets the Brazilian
 * wording as on Android, Chinese only in its simplified script (Android's `values-zh-rCN`), and English otherwise.
 */
export function pickLanguage(tags: readonly (string | null | undefined)[]): Language {
    for (const raw of tags) {
        if (!raw) continue;
        const parts = raw.replace(/_/g, "-").toLowerCase().split("-");
        const base = parts[0];
        if (base === "zh") {
            const rest = parts.slice(1);
            if (rest.includes("hant") || rest.includes("tw") || rest.includes("hk") || rest.includes("mo")) continue;
            return "zh";
        }
        if ((LANGUAGES as readonly string[]).includes(base)) return base as Language;
    }
    return "en";
}

/** Arabic draws right to left, as on Android. */
export const isRightToLeft = (language: Language) => language === "ar";

export type PluralForm = "zero" | "one" | "two" | "few" | "many" | "other";
export type PluralForms = Partial<Record<PluralForm, string>> & { other: string };

/**
 * Unicode's plural rules for whole numbers in Tuned's languages, written out because Intl.PluralRules isn't on every
 * TV (Hermes on Vega OS). Russian and Polish use one, few and many; Arabic all six; Chinese only other.
 */
export function pluralForm(language: Language, n: number): PluralForm {
    const i = Math.abs(Math.trunc(n));
    const d10 = i % 10;
    const d100 = i % 100;
    switch (language) {
        case "zh":
            return "other";
        case "fr":
        case "pt":
        case "hi":
            return i === 0 || i === 1 ? "one" : "other";
        case "ru":
            if (d10 === 1 && d100 !== 11) return "one";
            if (d10 >= 2 && d10 <= 4 && (d100 < 12 || d100 > 14)) return "few";
            return "many";
        case "pl":
            if (i === 1) return "one";
            if (d10 >= 2 && d10 <= 4 && (d100 < 12 || d100 > 14)) return "few";
            return "many";
        case "ar":
            if (i === 0) return "zero";
            if (i === 1) return "one";
            if (i === 2) return "two";
            if (d100 >= 3 && d100 <= 10) return "few";
            if (d100 >= 11) return "many";
            return "other";
        default:
            return i === 1 ? "one" : "other";
    }
}

/** The form of a word for `n` in `language`, falling back to `other` when a language doesn't need that form. */
export function plural(language: Language, n: number, forms: PluralForms): string {
    return forms[pluralForm(language, n)] ?? forms.other;
}

/**
 * Every key of `english` that `translation` lacks, as dotted paths, and every value whose kind differs (text where
 * English has a function). Each app's tests run it for every language, as Android Lint's MissingTranslation does.
 */
export function missingKeys(english: object, translation: object, at = ""): string[] {
    const missing: string[] = [];
    const theirs = translation as Record<string, unknown>;
    for (const [key, value] of Object.entries(english)) {
        const path = at ? `${at}.${key}` : key;
        const other = theirs[key];
        if (other === undefined || typeof other !== typeof value) missing.push(path);
        else if (value && typeof value === "object") missing.push(...missingKeys(value, other as object, path));
    }
    return missing;
}
