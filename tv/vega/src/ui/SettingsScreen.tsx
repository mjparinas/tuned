// Settings: the Tuned account and Premium (Premium comes only with the account on Fire TV; no prices, no buy
// buttons, no store links), refreshing the channels and signing out. Every button answers at once with a spinner and
// then says how it ended (docs/decisions/settings.md#every-button-says-it-did-something-melvin-2026-11-21).
import * as React from "react";
import { ScrollView, Text, View } from "react-native";
import { premiumText, type AppModel, type State } from "../app/model";
import { ProgressLines } from "./LoadingScreen";
import { Button, Screen } from "./parts";
import { TopBar } from "./TopBar";
import { t } from "./strings";
import { colours, useDp } from "./theme";

export const VERSION = "0.1.0";

function Section({ title, children }: { title: string; children: React.ReactNode }) {
    const dp = useDp();
    return (
        <View style={{ backgroundColor: colours.card, borderRadius: dp(20), padding: dp(20), gap: dp(12), marginBottom: dp(16) }}>
            <Text style={{ color: colours.text, fontSize: dp(18), fontWeight: "600" }}>{title}</Text>
            {children}
        </View>
    );
}

const Line = ({ text, tone = "muted" }: { text: string; tone?: "muted" | "ok" | "bad" }) => {
    const dp = useDp();
    const colour = tone === "ok" ? colours.teal : tone === "bad" ? colours.danger : colours.muted;
    return <Text style={{ color: colour, fontSize: dp(14), lineHeight: dp(21) }}>{text}</Text>;
};

export function SettingsScreen({ model, state }: { model: AppModel; state: State }) {
    const dp = useDp();
    const account = state.account;
    const check = state.accountCheck;
    const refresh = state.refresh;
    const library = state.libraryLoad;
    const premium = premiumText(account);
    return (
        <Screen>
            <TopBar model={model} tab="settings" />
            <ScrollView style={{ flex: 1, marginTop: dp(14) }} contentContainerStyle={{ maxWidth: dp(640) }}>
                <Text style={{ color: colours.text, fontSize: dp(26), fontWeight: "600", marginBottom: dp(14) }}>{t.settings}</Text>
                <Section title={t.accountTitle}>
                    {account ? <Line text={t.accountAs(account.email)} /> : null}
                    <Line
                        text={check.busy ? t.checking : premium}
                        tone={account?.seat === "active" ? "ok" : account?.seat === "full" ? "bad" : "muted"}
                    />
                    {check.result && !check.busy && check.result.text !== premium ? (
                        <Line text={check.result.text} tone={check.result.ok ? "ok" : "bad"} />
                    ) : null}
                    <View style={{ flexDirection: "row", gap: dp(12), flexWrap: "wrap" }}>
                        {account ? (
                            <>
                                <Button label={t.checkAgain} filled start busy={check.busy} onPress={() => void model.checkAccount()} />
                                <Button label={t.accountSignOut} onPress={() => void model.signOutOfAccount()} />
                            </>
                        ) : (
                            <Button label={t.accountSignIn} filled start onPress={() => model.go("account")} />
                        )}
                    </View>
                </Section>
                <Section title={t.playlistTitle}>
                    {state.lineup ? (
                        <Line
                            text={[
                                t.channels(state.lineup.channels.length),
                                ...(state.library
                                    ? [t.movieCount(state.library.movies.length), t.showCount(state.library.shows.length)]
                                    : []),
                            ].join(", ")}
                        />
                    ) : null}
                    {refresh.busy ? <ProgressLines progress={refresh.progress ?? null} /> : null}
                    {!refresh.busy && refresh.result ? <Line text={refresh.result.text} tone={refresh.result.ok ? "ok" : "bad"} /> : null}
                    {library.busy ? <ProgressLines progress={library.progress ?? null} /> : null}
                    {!library.busy && library.result && state.login?.kind === "xtream" ? (
                        <Line text={library.result.text} tone={library.result.ok ? "ok" : "bad"} />
                    ) : null}
                    <View style={{ flexDirection: "row", gap: dp(12), flexWrap: "wrap" }}>
                        <Button
                            label={refresh.result && !refresh.result.ok ? t.tryAgain : t.refreshNow}
                            busy={refresh.busy || library.busy}
                            onPress={() => void model.refreshNow()}
                        />
                        <Button label={t.signOut} onPress={() => void model.signOut()} />
                    </View>
                    <Line text={t.signOutHint} />
                </Section>
                <Line text={t.about(VERSION)} />
                <Line text={t.noContent} />
            </ScrollView>
        </Screen>
    );
}
