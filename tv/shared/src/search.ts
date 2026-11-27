// Search for the TV apps, the way core/Search.kt does it on Android: lower case, no accents, single spaces, and every
// word typed must start a word of the name ("tele que" finds "Télé-Québec"). Plain TypeScript, with no tv/core, so
// Vega's Hermes bundle takes it on its own (`@tuned/shared/search`).

const ACCENTS: Record<string, string> = {
    à: "a",
    á: "a",
    â: "a",
    ã: "a",
    ä: "a",
    å: "a",
    ā: "a",
    ą: "a",
    æ: "ae",
    ç: "c",
    ć: "c",
    č: "c",
    è: "e",
    é: "e",
    ê: "e",
    ë: "e",
    ē: "e",
    ę: "e",
    ě: "e",
    ì: "i",
    í: "i",
    î: "i",
    ï: "i",
    ī: "i",
    ı: "i",
    ł: "l",
    ñ: "n",
    ń: "n",
    ň: "n",
    ò: "o",
    ó: "o",
    ô: "o",
    õ: "o",
    ö: "o",
    ø: "o",
    ō: "o",
    ő: "o",
    œ: "oe",
    ř: "r",
    ś: "s",
    š: "s",
    ş: "s",
    ș: "s",
    ß: "ss",
    ť: "t",
    ț: "t",
    ù: "u",
    ú: "u",
    û: "u",
    ü: "u",
    ū: "u",
    ů: "u",
    ű: "u",
    ý: "y",
    ÿ: "y",
    ź: "z",
    ż: "z",
    ž: "z",
    ğ: "g",
};

/** Whether `normalize` takes accents off here (Hermes builds without Intl may not have it). */
const decomposes = (() => {
    try {
        return "é".normalize("NFD").length === 2;
    } catch {
        return false;
    }
})();

/** "Télé-Québec HD" is "tele quebec hd". */
export function searchText(text: string): string {
    let out = text.toLowerCase();
    out = decomposes ? out.normalize("NFD").replace(/[̀-ͯ]/g, "") : out;
    out = out.replace(/[^\u0000-\u007f]/g, (c) => ACCENTS[c] ?? c);
    // Spaces and punctuation, spelled out rather than \p{L}, which not every TV's engine has.
    return out.replace(/[\s!-/:-@[-`{-~\u00a0-\u00bf\u2000-\u206f\u3000-\u303f\uff01-\uff0f]+/g, " ").trim();
}

/** A matcher for what was typed: every word must start a word of the name. Null when nothing was typed. */
export function matcher(query: string): ((name: string) => boolean) | null {
    const words = searchText(query).split(" ").filter(Boolean);
    if (!words.length) return null;
    return (name) => {
        const text = ` ${searchText(name)}`;
        return words.every((w) => text.includes(` ${w}`));
    };
}

/** The items whose name matches, in their own order, at most `limit` of them. */
export function search<T>(items: readonly T[], query: string, name: (item: T) => string, limit = 500): T[] {
    const match = matcher(query);
    if (!match) return [];
    const out: T[] = [];
    for (const item of items) {
        if (match(name(item))) out.push(item);
        if (out.length >= limit) break;
    }
    return out;
}
