import assert from "node:assert/strict";
import { test } from "node:test";
import { LANGUAGES, missingKeys } from "@tuned/shared/i18n";
import { en } from "./strings/en.ts";
import { TRANSLATIONS } from "./strings.ts";

// As Android Lint's MissingTranslation: every line in English is in every language (docs/decisions/languages.md).
test("every language has every line English has", () => {
    for (const language of LANGUAGES) assert.deepEqual(missingKeys(en, TRANSLATIONS[language]), [], language);
});

test("every line reads in every language, with Tuned and Premium left as they are", () => {
    for (const language of LANGUAGES) {
        const t = TRANSLATIONS[language];
        const texts = [
            t.noContent,
            t.signInDigits("12"),
            t.codeExpiresIn(5),
            t.stepOf(2, 3),
            t.resume("12:34"),
            t.season(2),
            t.episodeNumber(3),
            t.upToDate(t.minutesAgo(5)),
            t.accountSignedIn("a@b.c"),
            t.track(0),
            t.hoursAgo(2),
            ...["s:40", "m:1", "m:3", "m:22", "h:1", "h:5"].map((eta) => t.left(eta)),
            ...Object.values(t.steps),
            ...Object.values(t.loadFailed),
        ];
        for (const text of texts) {
            assert.ok(text.trim().length > 0, `${language}: empty line`);
            assert.ok(!/undefined|NaN|\$\{/.test(text), `${language}: ${text}`);
        }
        assert.match(t.noContent, /Tuned/, language);
        assert.equal(t.accountPremium, "Premium", language);
        if (language !== "en") assert.notEqual(t.settings, en.settings, language);
    }
});

test("English says Movies, never Films", () => {
    assert.ok(!JSON.stringify(en).includes("Film"));
});
