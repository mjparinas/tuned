import assert from "node:assert/strict";
import { test } from "node:test";
import { LANGUAGES, missingKeys, plural, pluralForm, pickLanguage } from "./i18n.ts";

test("picks the TV's language as Android picks a values folder, and English otherwise", () => {
    assert.equal(pickLanguage(["de-DE"]), "de");
    assert.equal(pickLanguage(["pt_PT"]), "pt");
    assert.equal(pickLanguage(["zh-CN"]), "zh");
    assert.equal(pickLanguage(["zh-Hans-SG"]), "zh");
    assert.equal(pickLanguage(["zh-TW", "fr-CA"]), "fr");
    assert.equal(pickLanguage(["ja-JP"]), "en");
    assert.equal(pickLanguage([undefined, "", "PL"]), "pl");
    assert.equal(pickLanguage([]), "en");
});

test("plural forms match Unicode's rules for every language Tuned has", () => {
    for (const language of LANGUAGES) {
        const rules = new Intl.PluralRules(language === "zh" ? "zh-CN" : language);
        for (let n = 0; n <= 250; n++) assert.equal(pluralForm(language, n), rules.select(n), `${language} ${n}`);
    }
    assert.equal(plural("ru", 3, { one: "канал", few: "канала", many: "каналов", other: "канала" }), "канала");
    assert.equal(plural("de", 3, { one: "Sender", other: "Sender" }), "Sender");
});

test("finds keys a translation lacks, even inside a group", () => {
    const en = { a: "A", b: (n: number) => `${n}`, group: { x: "X", y: "Y" } };
    assert.deepEqual(missingKeys(en, { a: "A", b: "2", group: { x: "X" } }), ["b", "group.y"]);
    assert.deepEqual(missingKeys(en, { a: "a", b: () => "", group: { x: "", y: "" } }), []);
});
