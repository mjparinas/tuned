import { LANGUAGES, missingKeys } from "@tuned/shared/i18n";
import { en } from "../src/ui/strings/en";
import { chooseLanguage, t, TRANSLATIONS } from "../src/ui/strings";

// As Android Lint's MissingTranslation: every line in English is in every language (docs/decisions/languages.md).
describe("the stick's languages", () => {
    afterEach(() => chooseLanguage(["en-CA"]));

    it("has every line English has, in every language", () => {
        for (const language of LANGUAGES) expect([language, missingKeys(en, TRANSLATIONS[language])]).toEqual([language, []]);
    });

    it("reads every line with numbers in every language, keeping Tuned and Premium as they are", () => {
        for (const language of LANGUAGES) {
            const s = TRANSLATIONS[language];
            const lines = [
                s.signInDigits("12"),
                ...[0, 1, 2, 3, 5, 11, 22, 1234].flatMap((n) => [s.channels(n), s.movieCount(n), s.showCount(n), s.episodeCount(n)]),
                s.readingChannels(1500),
                s.stepLine(2, 4, s.downloadingMovies),
                s.refreshStep(s.refreshing, 2, 3, s.downloadingChannels),
                s.bytesOf("4.2 MB", "18 MB"),
                s.bytesSoFar("4.2 MB"),
                s.minutesLeft(3),
                s.hoursLeft(1, 5),
                s.seasonEpisode(1, 2),
                s.episodeShort(1, 2),
                s.resume("12:34"),
                s.rating(7.4),
                s.about("1.0.0"),
                ...Object.values(s.failed),
            ];
            for (const line of lines) expect(line).not.toMatch(/undefined|NaN|\$\{|^\s*$/);
            expect(s.noContent).toContain("Tuned");
            expect(s.premiumOn).toContain("Premium");
        }
    });

    it("picks the TV's language, and English for one Tuned doesn't have", () => {
        expect(chooseLanguage(["de_DE"])).toBe("de");
        expect(t.settings).toBe("Einstellungen");
        expect(t.channels(1234)).toBe("1.234 Sender");
        expect(chooseLanguage(["ru-RU"])).toBe("ru");
        expect([t.channels(1), t.channels(3), t.channels(5), t.channels(21)]).toEqual(["1 канал", "3 канала", "5 каналов", "21 канал"]);
        expect(chooseLanguage(["ja-JP"])).toBe("en");
        expect(t.settings).toBe("Settings");
    });

    it("says Movies, never Films, in English", () => {
        expect(JSON.stringify(en)).not.toMatch(/[Ff]ilm/);
    });
});
