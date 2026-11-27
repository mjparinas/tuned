// What a person can hide on a TV, by the same rules as Android's :core, so a channel hidden on the phone is hidden on
// the TV too. Adult categories (core/Classifier.isAdult) and round-the-clock "24/7" channels (core/RoundTheClock).
// Keep these in step with the Kotlin. `@tuned/shared/filters`.

const adultWords = new RegExp(
    String.raw`\bxxx\b|\badult(s|os?|es?|i)?\b|\b18\s?\+|\+\s?18\b|\bporn|\bhentai\b|\berot(ic|ik|ique|ica)\b|` +
        String.raw`\berwachsene|\bvolwassen|\bbrazzers\b|\bhustler\b|\bplayboy\b|\bpenthouse\b|\bdorcel\b|` +
        String.raw`\bred\s?light\b|\bbabestation\b`,
    "i",
);

/** Names that say "adult" but are for everyone. */
const notAdult = /\badult\s*swim\b|\byoung\s+adults?\b|\bnew\s+adult\b/gi;

const adult = (text: string) => adultWords.test(text.replace(notAdult, " "));

/** An adult category or stream, by its category's name or its own. */
export function isAdult(group: string, name: string): boolean {
    return adult(group) || adult(name);
}

// No lookbehind, which older engines lack: the text is padded with a space at each end instead, as the SQL does.
const roundTheClock = /[^0-9]24(?:[/ x-]| \/ )?7[^0-9]/;

/** "24/7 Friends", "US| 24/7 CARTOONS", "24-7 Seinfeld", "247: The Simpsons", "Friends 24x7". */
export function is247(group: string, name: string): boolean {
    return roundTheClock.test(` ${group.toLowerCase()} ${name.toLowerCase()} `);
}

export interface Hiding {
    /** On by default, as on Android, where adult categories stay hidden until unlocked. */
    hideAdult: boolean;
    /** Off by default, since many people watch them. */
    hide247: boolean;
}

/**
 * Keeps what isn't hidden. A category's name is checked once, so a big list costs one test per item for its own name.
 * `categoryName` gives the name of an item's category ("" when it has none).
 */
export function visible<T>(items: readonly T[], hiding: Hiding, name: (item: T) => string, categoryName: (item: T) => string): T[] {
    if (!hiding.hideAdult && !hiding.hide247) return items.slice();
    const groups = new Map<string, boolean>();
    const groupHidden = (group: string) => {
        let hidden = groups.get(group);
        if (hidden === undefined) {
            hidden = (hiding.hideAdult && adult(group)) || (hiding.hide247 && is247(group, ""));
            groups.set(group, hidden);
        }
        return hidden;
    };
    return items.filter((item) => {
        if (groupHidden(categoryName(item))) return false;
        const own = name(item);
        return !((hiding.hideAdult && adult(own)) || (hiding.hide247 && is247("", own)));
    });
}
