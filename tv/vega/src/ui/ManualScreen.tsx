// Typing a login with the remote, for someone without a phone: an Xtream login (a pasted get.php link fills all
// three boxes, as on Android) or an M3U link. The stick's own keyboard opens on each box.
import * as React from "react";
import { Text, TextInput, View } from "react-native";
import type { AppModel } from "../app/model";
import type { Login } from "../core/models";
import { fromLink } from "../core/xtream";
import { Brand, Button, Screen } from "./parts";
import { t } from "./strings";
import { colours, useDp } from "./theme";

function Field({
    label,
    value,
    onChange,
    secret,
    start,
}: {
    label: string;
    value: string;
    onChange: (v: string) => void;
    secret?: boolean;
    start?: boolean;
}) {
    const dp = useDp();
    const [focused, setFocused] = React.useState(false);
    return (
        <View style={{ gap: dp(6) }}>
            <Text style={{ color: colours.muted, fontSize: dp(13) }}>{label}</Text>
            <TextInput
                accessibilityLabel={label}
                hasTVPreferredFocus={start}
                value={value}
                onChangeText={onChange}
                secureTextEntry={secret}
                autoCapitalize="none"
                autoCorrect={false}
                onFocus={() => setFocused(true)}
                onBlur={() => setFocused(false)}
                style={{
                    minHeight: dp(44),
                    borderRadius: dp(14),
                    borderWidth: dp(2),
                    borderColor: focused ? colours.focus : colours.line,
                    backgroundColor: colours.card,
                    color: colours.text,
                    fontSize: dp(16),
                    paddingHorizontal: dp(14),
                }}
            />
        </View>
    );
}

export function ManualScreen({ model }: { model: AppModel }) {
    const dp = useDp();
    const [kind, setKind] = React.useState<"xtream" | "link">("xtream");
    const [server, setServer] = React.useState("");
    const [username, setUsername] = React.useState("");
    const [password, setPassword] = React.useState("");
    const [playlist, setPlaylist] = React.useState("");
    const [guide, setGuide] = React.useState("");
    const [problem, setProblem] = React.useState<string | null>(null);

    const typedServer = (value: string) => {
        const pasted = fromLink(value);
        if (pasted) {
            setServer(pasted.server);
            setUsername(pasted.username);
            setPassword(pasted.password);
        } else setServer(value);
    };

    const submit = () => {
        let login: Login | null = null;
        if (kind === "xtream" && server.trim() && username.trim() && password)
            login = { kind, server: server.trim(), username: username.trim(), password };
        if (kind === "link" && playlist.trim()) {
            const pasted = fromLink(playlist);
            login = pasted ?? {
                kind,
                playlistUrl: withScheme(playlist.trim()),
                guideUrl: guide.trim() ? withScheme(guide.trim()) : undefined,
            };
        }
        if (!login) return setProblem(kind === "xtream" ? t.fillXtream : t.fillLink);
        setProblem(null);
        void model.useLogin(login);
    };

    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, maxWidth: dp(560), gap: dp(14), marginTop: dp(18) }}>
                <View style={{ flexDirection: "row", gap: dp(10) }}>
                    <Button label={t.xtream} filled={kind === "xtream"} onPress={() => setKind("xtream")} />
                    <Button label={t.link} filled={kind === "link"} onPress={() => setKind("link")} />
                </View>
                {kind === "xtream" ? (
                    <>
                        <Field label={t.server} value={server} onChange={typedServer} start />
                        <Field label={t.username} value={username} onChange={setUsername} />
                        <Field label={t.password} value={password} onChange={setPassword} secret />
                    </>
                ) : (
                    <>
                        <Field label={t.playlistUrl} value={playlist} onChange={setPlaylist} start />
                        <Field label={t.guideUrl} value={guide} onChange={setGuide} />
                    </>
                )}
                {problem ? <Text style={{ color: colours.danger, fontSize: dp(14) }}>{problem}</Text> : null}
                <View style={{ flexDirection: "row", gap: dp(12), marginTop: dp(4) }}>
                    <Button label={t.signIn} filled onPress={submit} />
                    <Button label={t.back} onPress={() => model.go("signin")} />
                </View>
            </View>
            <Text style={{ color: colours.muted, fontSize: dp(12) }}>{t.noContent}</Text>
        </Screen>
    );
}

const withScheme = (url: string) => (/^[a-z]+:\/\//i.test(url) ? url : `http://${url}`);
