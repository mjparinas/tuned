// Sign in with your phone (docs/decisions/onboarding.md): the relay's code and QR, the phone at tunedplayer.com/tv
// sends a playlist sealed for this stick or signs it in to a Tuned account. The same screen signs in to the account
// from Settings (`mode="account"`). Typing a login with the remote is one button away.
import * as React from "react";
import { Text, View } from "react-native";
import type { SignInCode } from "@tuned/shared/account";
import type { AppModel } from "../app/model";
import { toLogin } from "../app/logins";
import { log } from "../core/log";
import { Brand, Button, OneLineCode, Screen, Spinner } from "./parts";
import { Qr } from "./Qr";
import { t } from "./strings";
import { colours, useDp } from "./theme";

export type RelayView =
    | { kind: "loading" }
    | { kind: "offline" }
    | { kind: "showing"; code: string; site: string; digits: string; qr: string }
    | { kind: "arrived" }
    | { kind: "failed"; text: string };

const POLL_MS = 3000;
const MAX_MISSES = 5;
const MARGIN_S = 15;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Shows codes and waits for the phone, as TvSignInScreen does on Android, until something arrives or it's left. */
export function useRelay(model: AppModel, mode: "playlist" | "account", round: number): RelayView {
    const [view, setView] = React.useState<RelayView>({ kind: "loading" });
    React.useEffect(() => {
        if (round < 0) return;
        let gone = false;
        (async () => {
            while (!gone) {
                setView({ kind: "loading" });
                const code = await model.account.newCode();
                if (gone) return;
                if (!code) return setView({ kind: "offline" });
                setView({
                    kind: "showing",
                    code: code.display,
                    site: `${model.site.replace(/^https?:\/\//, "")}/tv`,
                    digits: code.digits,
                    qr: code.link,
                });
                const result = await wait(model, code, mode, () => gone, setView);
                if (result !== "again") return;
            }
        })().catch((error) => {
            log.error("SignIn", "relay", error);
            if (!gone) setView({ kind: "offline" });
        });
        return () => {
            gone = true;
        };
    }, [model, mode, round]);
    return view;
}

async function wait(
    model: AppModel,
    code: SignInCode,
    mode: string,
    gone: () => boolean,
    setView: (v: RelayView) => void,
): Promise<"again" | "done"> {
    const until = code.expiresAt - MARGIN_S * 1000;
    let misses = 0;
    while (Date.now() < until) {
        await sleep(POLL_MS);
        if (gone()) return "done";
        const taken = await model.account.take(code);
        if (taken.kind === "waiting") misses = 0;
        else if (taken.kind === "gone") return "again";
        else if (taken.kind === "unreachable") {
            if (++misses >= MAX_MISSES) {
                setView({ kind: "offline" });
                return "done";
            }
        } else if (taken.kind === "signed in") {
            setView({ kind: "arrived" });
            await model.accountSignedIn(taken.state);
            if (mode === "account") model.go("settings");
            else if (!model.state.login)
                setView({
                    kind: "failed",
                    text: t.noPlaylistOnAccount,
                });
            return "done";
        } else {
            setView({ kind: "arrived" });
            if (taken.signedIn) await model.accountSignedIn(await model.account.state());
            const login = toLogin(taken.playlists[0]);
            if (login) await model.useLogin(login);
            else if (mode === "account" && taken.signedIn) model.go("settings");
            else setView({ kind: "failed", text: t.nothingUsable });
            return "done";
        }
    }
    return "again";
}

export function SignInScreen({ model, mode, view: shown }: { model: AppModel; mode: "playlist" | "account"; view?: RelayView }) {
    const dp = useDp();
    const [round, setRound] = React.useState(0);
    const live = useRelay(model, mode, shown ? -1 : round);
    const view = shown ?? live;
    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, flexDirection: "row", gap: dp(48), alignItems: "center", marginTop: dp(8) }}>
                <View style={{ flex: 1.1, gap: dp(14) }}>
                    <Text style={{ color: colours.text, fontSize: dp(30), fontWeight: "600" }}>
                        {mode === "account" ? t.accountSignIn : t.signInTitle}
                    </Text>
                    <Text style={{ color: colours.muted, fontSize: dp(15), lineHeight: dp(22) }}>
                        {mode === "account" ? t.accountSignedOut : t.signInWhat}
                    </Text>
                    {view.kind === "showing" ? (
                        <View style={{ gap: dp(10), marginTop: dp(6) }}>
                            <Text style={{ color: colours.muted, fontSize: dp(15) }}>{t.signInStep1}</Text>
                            <Text numberOfLines={1} style={{ color: colours.primary, fontSize: dp(24), fontWeight: "600" }}>
                                {view.site}
                            </Text>
                            <Text style={{ color: colours.muted, fontSize: dp(15), marginTop: dp(6) }}>{t.signInStep2}</Text>
                            <OneLineCode code={view.code} size={dp(46)} />
                            <Text style={{ color: colours.muted, fontSize: dp(13) }}>{t.signInDigits(view.digits)}</Text>
                        </View>
                    ) : null}
                    {view.kind === "loading" ? <Spinner line={t.gettingCode} /> : null}
                    {view.kind === "arrived" ? <Spinner line={t.arrived} /> : null}
                    {view.kind === "offline" || view.kind === "failed" ? (
                        <Text style={{ color: colours.danger, fontSize: dp(15), lineHeight: dp(22) }}>
                            {view.kind === "offline" ? t.relayOffline : view.text}
                        </Text>
                    ) : null}
                    <View style={{ flexDirection: "row", gap: dp(12), marginTop: dp(12) }}>
                        {view.kind === "offline" || view.kind === "failed" ? (
                            <Button label={t.tryAgain} filled start onPress={() => setRound((r) => r + 1)} />
                        ) : null}
                        {mode === "playlist" ? (
                            <Button
                                label={t.typeInstead}
                                start={view.kind === "showing" || view.kind === "loading"}
                                onPress={() => model.go("manual")}
                            />
                        ) : (
                            <Button label={t.back} start={view.kind === "showing"} onPress={() => model.go("settings")} />
                        )}
                    </View>
                </View>
                <View style={{ flex: 0.9, alignItems: "center", justifyContent: "center" }}>
                    {view.kind === "showing" ? (
                        <Qr text={view.qr} size={dp(300)} />
                    ) : (
                        <View style={{ width: dp(300), height: dp(300), borderRadius: dp(20), backgroundColor: colours.card }} />
                    )}
                </View>
            </View>
            <Text style={{ color: colours.muted, fontSize: dp(12) }}>{t.noContent}</Text>
        </Screen>
    );
}
