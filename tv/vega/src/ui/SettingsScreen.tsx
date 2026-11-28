// Settings: the Tuned account and Premium (Premium comes only with the account on Fire TV; no prices, no buy
// buttons, no store links), refreshing the channels and signing out. Every button answers at once with a spinner and
// then says how it ended (docs/decisions/settings.md#every-button-says-it-did-something-melvin-2026-11-21).
import * as React from "react";
import { ScrollView, Text, View } from "react-native";
import { hasPremium, premiumText, TRACKERS, TUNE_WAITS, type AppModel, type State, type Tracker } from "../app/model";
import { ProgressLines } from "./LoadingScreen";
import { Button, Focusable, OneLineCode, Screen, Spinner } from "./parts";
import { ManageLists } from "./AddToList";
import { Avatar, PinPad } from "./ProfilesScreen";
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

/** A row with a few choices: OK moves to the next, and the one picked is said in words. */
function Choice({ label, value, onPress }: { label: string; value: string; onPress: () => void }) {
    const dp = useDp();
    return (
        <Focusable
            onPress={onPress}
            accessibilityRole="button"
            accessibilityLabel={`${label}: ${value}`}
            style={{
                minHeight: dp(48),
                borderRadius: dp(14),
                paddingHorizontal: dp(14),
                paddingVertical: dp(10),
                backgroundColor: colours.card2,
                flexDirection: "row",
                alignItems: "center",
            }}
            focusedStyle={{ backgroundColor: colours.line }}
        >
            {() => (
                <View style={{ flexDirection: "row", alignItems: "center", gap: dp(14), flex: 1 }}>
                    <Text style={{ flex: 1, color: colours.text, fontSize: dp(15), fontWeight: "600" }}>{label}</Text>
                    <Text style={{ color: colours.primary, fontSize: dp(15), fontWeight: "600" }}>{value}</Text>
                </View>
            )}
        </Focusable>
    );
}

/** A switch row: OK turns it on or off, and the state is said in words as well as colour (accessibility.md). */
function Toggle({ label, hint, on, onChange }: { label: string; hint: string; on: boolean; onChange: (on: boolean) => void }) {
    const dp = useDp();
    return (
        <Focusable
            onPress={() => onChange(!on)}
            accessibilityRole="switch"
            accessibilityLabel={label}
            accessibilityState={{ checked: on }}
            style={{
                minHeight: dp(48),
                borderRadius: dp(14),
                paddingHorizontal: dp(14),
                paddingVertical: dp(10),
                backgroundColor: colours.card2,
            }}
            focusedStyle={{ backgroundColor: colours.line }}
        >
            {() => (
                <View style={{ flexDirection: "row", alignItems: "center", gap: dp(14) }}>
                    <View style={{ flex: 1, gap: dp(4) }}>
                        <Text style={{ color: colours.text, fontSize: dp(15), fontWeight: "600" }}>{label}</Text>
                        <Text style={{ color: colours.muted, fontSize: dp(13), lineHeight: dp(19) }}>{hint}</Text>
                    </View>
                    <View
                        style={{
                            minWidth: dp(56),
                            minHeight: dp(28),
                            borderRadius: dp(14),
                            paddingHorizontal: dp(10),
                            alignItems: "center",
                            justifyContent: "center",
                            backgroundColor: on ? colours.primary : colours.card,
                        }}
                    >
                        <Text style={{ color: on ? colours.onPrimary : colours.muted, fontSize: dp(13), fontWeight: "700" }}>
                            {on ? t.on : t.off}
                        </Text>
                    </View>
                </View>
            )}
        </Focusable>
    );
}

/** Each service's words: its name and the lines that say it, the rest shared. */
const TRACKER_WORDS = {
    trakt: () => ({
        name: t.trakt,
        hint: t.traktHint,
        connect: t.traktConnect,
        scrobbleHint: t.traktScrobbleHint,
        problem: t.traktProblem,
        premium: t.traktPremium,
    }),
    simkl: () => ({
        name: t.simkl,
        hint: t.simklHint,
        connect: t.simklConnect,
        scrobbleHint: t.simklScrobbleHint,
        problem: t.simklProblem,
        premium: t.simklPremium,
    }),
};

/**
 * Settings › Trakt and Settings › Simkl ([trakt](trakt.md), [simkl](simkl.md)): Connect shows a code to type at
 * trakt.tv/activate or simkl.com/pin with a spinner while the service is asked, Back stops it; once in, Scrobble what you
 * watch and Disconnect. Premium, as on Android.
 */
function TrackerSection({ model, state, name }: { model: AppModel; state: State; name: Tracker }) {
    const dp = useDp();
    const view = state.trackers[name];
    const words = TRACKER_WORDS[name]();
    const code = view.code;
    // The remote's Back stops waiting for the code, not the screen under it.
    React.useEffect(() => {
        if (!code) return;
        model.onBack = () => {
            model.cancelTracker(name);
            return true;
        };
        return () => {
            model.onBack = null;
        };
    }, [model, code, name]);
    if (!model.trackerOn(name)) return <Line text={words.premium} />;
    if (code) {
        const site = code.verificationUrl.replace(/^https?:\/\//, "");
        return (
            <View style={{ gap: dp(10) }}>
                <Line text={t.trackerCode(site)} />
                <OneLineCode code={code.userCode} size={dp(40)} />
                <Spinner line={t.trackerWaiting} />
                <View style={{ flexDirection: "row" }}>
                    <Button label={t.back} start onPress={() => model.cancelTracker(name)} />
                </View>
            </View>
        );
    }
    return (
        <View style={{ gap: dp(10) }}>
            <Line text={view.signedIn ? t.trackerConnected(view.user) : words.hint} tone={view.signedIn ? "ok" : "muted"} />
            {view.problem ? <Line text={words.problem[view.problem]} tone="bad" /> : null}
            {view.signedIn ? (
                <>
                    <Toggle
                        label={t.trackerScrobble}
                        hint={words.scrobbleHint}
                        on={view.scrobble}
                        onChange={(on) => void model.setTrackerScrobble(name, on)}
                    />
                    <View style={{ flexDirection: "row" }}>
                        <Button label={t.trackerDisconnect} busy={view.busy} onPress={() => void model.disconnectTracker(name)} />
                    </View>
                </>
            ) : (
                <View style={{ flexDirection: "row" }}>
                    <Button label={words.connect} busy={view.busy} onPress={() => void model.connectTracker(name)} />
                </View>
            )}
        </View>
    );
}

export function SettingsScreen({ model, state }: { model: AppModel; state: State }) {
    const dp = useDp();
    const account = state.account;
    const check = state.accountCheck;
    const refresh = state.refresh;
    const library = state.libraryLoad;
    const premium = premiumText(account);
    const [askAdult, setAskAdult] = React.useState(false);
    const profile = state.profiles.profiles.length > 1 ? model.currentProfile() : null;
    const kids = model.kids();
    if (askAdult) {
        // Showing adult categories asks for the parental PIN the phone set, as on Android.
        return (
            <PinPad
                model={model}
                title={t.adultPin}
                hash={state.profiles.parentalPin}
                onCancel={() => setAskAdult(false)}
                onOk={() => {
                    setAskAdult(false);
                    void model.setPrefs({ hideAdult: false });
                }}
            />
        );
    }
    return (
        <Screen>
            <TopBar model={model} state={state} tab="settings" />
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
                {profile ? (
                    <Section title={t.profiles}>
                        <View style={{ flexDirection: "row", alignItems: "center", gap: dp(12), minHeight: dp(48) }}>
                            <Avatar profile={profile} size={dp(36)} />
                            <Text numberOfLines={1} style={{ flex: 1, color: colours.text, fontSize: dp(15), fontWeight: "600" }}>
                                {t.watchingAs(profile.name)}
                            </Text>
                            <Button label={t.switchProfile} onPress={() => model.openProfiles()} />
                        </View>
                    </Section>
                ) : null}
                {model.canUseLists() ? (
                    <Section title={t.yourLists}>
                        <ManageLists model={model} state={state} />
                    </Section>
                ) : null}
                {TRACKERS.filter((name) => model.trackerAvailable(name)).map((name) => (
                    <Section key={name} title={TRACKER_WORDS[name]().name}>
                        <TrackerSection model={model} state={state} name={name} />
                    </Section>
                ))}
                <Section title={t.playlists}>
                    {state.playlists.map((p) => (
                        <View key={p.id} style={{ flexDirection: "row", alignItems: "center", gap: dp(12), minHeight: dp(48) }}>
                            <Text numberOfLines={1} style={{ flex: 1, color: colours.text, fontSize: dp(15), fontWeight: "600" }}>
                                {p.name}
                            </Text>
                            {p.id === state.playlistId ? (
                                <Text style={{ color: colours.teal, fontSize: dp(14) }}>{t.using}</Text>
                            ) : (
                                <>
                                    <Button label={t.use} onPress={() => void model.switchPlaylist(p.id)} />
                                    <Button label={t.remove} onPress={() => void model.removePlaylist(p.id)} />
                                </>
                            )}
                        </View>
                    ))}
                    {hasPremium(state.account) ? (
                        <View style={{ flexDirection: "row" }}>
                            <Button label={t.addPlaylist} onPress={() => model.startAdding()} />
                        </View>
                    ) : (
                        <Line text={t.premiumForMore} />
                    )}
                </Section>
                <Section title={t.contentTitle}>
                    {kids ? null : (
                        <Toggle
                            label={t.hideAdult}
                            hint={t.hideAdultHint}
                            on={state.prefs.hideAdult}
                            onChange={(hideAdult) =>
                                !hideAdult && state.profiles.parentalPin ? setAskAdult(true) : void model.setPrefs({ hideAdult })
                            }
                        />
                    )}
                    <Toggle
                        label={t.hide247}
                        hint={t.hide247Hint}
                        on={state.prefs.hide247}
                        onChange={(hide247) => void model.setPrefs({ hide247 })}
                    />
                </Section>
                <Section title={t.autoTune}>
                    <Choice
                        label={t.tuneWait}
                        value={t.seconds(state.prefs.tuneWaitS)}
                        onPress={() => {
                            const at = TUNE_WAITS.indexOf(state.prefs.tuneWaitS as (typeof TUNE_WAITS)[number]);
                            void model.setPrefs({ tuneWaitS: TUNE_WAITS[(at + 1) % TUNE_WAITS.length] });
                        }}
                    />
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
                {model.tmdb.enabled ? <Line text={t.tmdbNotice} /> : null}
                <Line text={t.noContent} />
            </ScrollView>
        </Screen>
    );
}
