// The stick's language, most wanted first (docs/decisions/languages.md): React Native's own locale from Vega OS, then
// what Hermes's Intl says, then the browser's in the preview. strings.ts picks the first one Tuned has.
import { I18nManager } from "react-native";

export function deviceLanguages(): string[] {
    const tags: string[] = [];
    try {
        const constants = (I18nManager as unknown as { getConstants?: () => { localeIdentifier?: string | null } }).getConstants?.();
        if (constants?.localeIdentifier) tags.push(constants.localeIdentifier);
    } catch {
        // Not every build of React Native has it; the next ones still do.
    }
    try {
        const intl = Intl.DateTimeFormat().resolvedOptions().locale;
        if (intl) tags.push(intl);
    } catch {
        // Hermes without Intl.
    }
    const nav = (globalThis as { navigator?: { languages?: readonly string[]; language?: string } }).navigator;
    if (nav?.languages?.length) tags.push(...nav.languages);
    else if (nav?.language) tags.push(nav.language);
    return tags;
}
