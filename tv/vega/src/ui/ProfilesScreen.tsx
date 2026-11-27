// "Who's watching?" (docs/decisions/profiles.md): the Tuned account's profiles in a row, the remote on the one used last
// (or the first), as on Android TV. A profile with its own PIN asks for it; leaving a kids profile for a grown-up one
// asks for the parental PIN. Profiles are added and changed on the phone or the website; the stick shows them.
import * as React from "react";
import { Text, View, type ViewStyle } from "react-native";
import { avatarGlyph, profileGradient, type Profile } from "@tuned/shared/profiles";
import type { AppModel, State } from "../app/model";
import { Brand, Button, Focusable, Screen, Spinner } from "./parts";
import { t } from "./strings";
import { colours, useDp } from "./theme";

export function Avatar({ profile, size }: { profile: Profile; size: number }) {
    const [from, to] = profileGradient(profile);
    const glyph = avatarGlyph(profile);
    const gradient = `linear-gradient(135deg, ${from}, ${to})`;
    return (
        <View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={[
                {
                    width: size,
                    height: size,
                    borderRadius: size * 0.28,
                    alignItems: "center",
                    justifyContent: "center",
                    backgroundColor: from,
                },
                { experimental_backgroundImage: gradient, backgroundImage: gradient } as unknown as ViewStyle,
            ]}
        >
            <Text style={{ color: "#ffffff", fontSize: size * (glyph ? 0.55 : 0.45), fontWeight: "700" }}>
                {glyph ?? (profile.name.trim()[0] ?? "?").toUpperCase()}
            </Text>
        </View>
    );
}

export function ProfilesScreen({ model, state, showFocused }: { model: AppModel; state: State; showFocused?: number }) {
    const dp = useDp();
    const [asking, setAsking] = React.useState<{ profile: Profile; lock: "profile" | "parental" } | null>(null);
    const list = state.profiles.profiles;
    const using = model.currentProfile();
    const startAt = Math.max(
        0,
        list.findIndex((p) => p.id === using?.id),
    );
    const pick = (p: Profile) => {
        const lock = model.lockFor(p);
        if (lock === "none") return void model.useProfile(p.id);
        setAsking({ profile: p, lock });
    };
    if (asking) {
        return (
            <PinPad
                model={model}
                title={asking.lock === "parental" ? t.parentalPinFor(asking.profile.name) : t.pinFor(asking.profile.name)}
                hash={asking.lock === "parental" ? state.profiles.parentalPin : asking.profile.pinHash}
                onCancel={() => setAsking(null)}
                onOk={() => {
                    setAsking(null);
                    void model.useProfile(asking.profile.id);
                }}
            />
        );
    }
    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: dp(28) }}>
                <Text accessibilityRole="header" style={{ color: colours.text, fontSize: dp(30), fontWeight: "600" }}>
                    {t.whosWatching}
                </Text>
                <View style={{ flexDirection: "row", gap: dp(22), flexWrap: "wrap", justifyContent: "center" }}>
                    {list.map((p, i) => {
                        const notes = [p.kids ? t.kidsProfile : "", p.pinHash ? t.pinLocked : ""].filter(Boolean);
                        return (
                            <Focusable
                                key={p.id}
                                start={i === startAt}
                                showFocused={showFocused === i}
                                onPress={() => pick(p)}
                                accessibilityRole="button"
                                accessibilityLabel={[p.name, ...notes].join(", ")}
                                accessibilityState={{ selected: p.id === using?.id }}
                                style={{ width: dp(128), alignItems: "center", gap: dp(8), padding: dp(10), borderRadius: dp(20) }}
                            >
                                {() => (
                                    <>
                                        <Avatar profile={p} size={dp(96)} />
                                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(16), fontWeight: "600" }}>
                                            {p.name}
                                        </Text>
                                        <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(12), minHeight: dp(16) }}>
                                            {notes.map((n) => (n === t.pinLocked ? `🔒 ${n}` : n)).join(" · ")}
                                        </Text>
                                    </>
                                )}
                            </Focusable>
                        );
                    })}
                </View>
                <Text style={{ color: colours.muted, fontSize: dp(13), textAlign: "center", maxWidth: dp(560) }}>{t.profilesOnPhone}</Text>
            </View>
        </Screen>
    );
}

/**
 * Four to eight digits picked on the pad on screen (a Fire TV remote has no number keys), checked against the hash the
 * phone made. Five wrong tries wait 30 seconds. Checking says so while it runs: 120,000 rounds take a moment.
 */
export function PinPad({
    model,
    title,
    hash,
    onOk,
    onCancel,
    preview,
}: {
    model: AppModel;
    title: string;
    hash: string | null;
    onOk: () => void;
    onCancel: () => void;
    /** For the preview's screenshots: digits typed and the line under them. */
    preview?: { typed: number; problem?: string; busy?: boolean };
}) {
    const dp = useDp();
    const [pin, setPin] = React.useState(preview ? "1".repeat(preview.typed) : "");
    const [busy, setBusy] = React.useState(!!preview?.busy);
    const [problem, setProblem] = React.useState<string | null>(preview?.problem ?? null);
    const type = (d: number) => {
        if (busy || pin.length >= 8) return;
        setProblem(null);
        setPin(pin + d);
    };
    const check = async () => {
        if (busy || pin.length < 4) return;
        setBusy(true);
        const answer = await model.checkPin(pin, hash);
        setBusy(false);
        if (answer === "ok") return onOk();
        setPin("");
        setProblem(answer === "wait" ? t.pinWait : t.pinWrong);
    };
    // The remote's Back closes the pad, not the screen under it.
    React.useEffect(() => {
        model.onBack = () => {
            onCancel();
            return true;
        };
        return () => {
            model.onBack = null;
        };
    }, [model, onCancel]);
    const key = { width: dp(64), minHeight: dp(48), justifyContent: "center", alignItems: "center", borderRadius: dp(24) } as const;
    const Digit = ({ d }: { d: number }) => (
        <Focusable
            start={d === 1}
            onPress={() => type(d)}
            accessibilityRole="button"
            accessibilityLabel={String(d)}
            style={[key, { backgroundColor: colours.card2 }]}
        >
            {() => <Text style={{ color: colours.text, fontSize: dp(20), fontWeight: "600" }}>{d}</Text>}
        </Focusable>
    );
    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: dp(14) }}>
                <Text accessibilityRole="header" style={{ color: colours.text, fontSize: dp(22), fontWeight: "600", textAlign: "center" }}>
                    {title}
                </Text>
                <View style={{ flexDirection: "row", gap: dp(10) }} accessibilityLabel={t.pinTyped(pin.length)}>
                    {Array.from({ length: Math.max(4, pin.length) }, (_, i) => (
                        <View
                            key={i}
                            style={{
                                width: dp(14),
                                height: dp(14),
                                borderRadius: dp(7),
                                borderWidth: dp(2),
                                borderColor: colours.primary,
                                backgroundColor: i < pin.length ? colours.primary : "transparent",
                            }}
                        />
                    ))}
                </View>
                <View style={{ minHeight: dp(24), justifyContent: "center" }} accessibilityLiveRegion="polite">
                    {busy ? (
                        <Spinner line={t.pinChecking} />
                    ) : (
                        <Text style={{ color: problem ? colours.danger : colours.muted, fontSize: dp(14) }}>{problem ?? t.pinPick}</Text>
                    )}
                </View>
                <View style={{ gap: dp(8) }}>
                    {[
                        [1, 2, 3],
                        [4, 5, 6],
                        [7, 8, 9],
                    ].map((row) => (
                        <View key={row[0]} style={{ flexDirection: "row", gap: dp(8) }}>
                            {row.map((d) => (
                                <Digit key={d} d={d} />
                            ))}
                        </View>
                    ))}
                    <View style={{ flexDirection: "row", gap: dp(8) }}>
                        <Focusable
                            onPress={() => setPin(pin.slice(0, -1))}
                            accessibilityRole="button"
                            accessibilityLabel={t.pinDelete}
                            style={[key, { backgroundColor: colours.card2 }]}
                        >
                            {() => <Text style={{ color: colours.text, fontSize: dp(18) }}>⌫</Text>}
                        </Focusable>
                        <Digit d={0} />
                        <Focusable
                            onPress={() => void check()}
                            accessibilityRole="button"
                            accessibilityLabel={t.ok}
                            accessibilityState={{ disabled: pin.length < 4, busy }}
                            style={[key, { backgroundColor: pin.length >= 4 ? colours.primary : colours.card2 }]}
                            focusedStyle={{ backgroundColor: pin.length >= 4 ? "#ffffff" : colours.line }}
                        >
                            {() => (
                                <Text
                                    style={{
                                        color: pin.length >= 4 ? colours.onPrimary : colours.muted,
                                        fontSize: dp(16),
                                        fontWeight: "700",
                                    }}
                                >
                                    {t.ok}
                                </Text>
                            )}
                        </Focusable>
                    </View>
                </View>
                <Button label={t.back} onPress={onCancel} />
            </View>
        </Screen>
    );
}
