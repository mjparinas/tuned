// The first download: each step numbered and named, bytes of the full size (or bytes so far) and the time left once
// it can tell; a failure says why in words, with Try again (AGENTS.md: waiting is never a mystery).
import * as React from "react";
import { Text, View } from "react-native";
import type { AppModel, State } from "../app/model";
import { detailLine, stepLine } from "../core/progress";
import { Brand, Button, ProgressBar, Screen, Spinner } from "./parts";
import { t } from "./strings";
import { colours, useDp } from "./theme";

export function ProgressLines({ progress }: { progress: NonNullable<State["loading"]["progress"]> | null }) {
    const dp = useDp();
    const detail = progress ? detailLine(progress) : null;
    const share = progress?.loaded !== undefined && progress.total ? progress.loaded / progress.total : null;
    return (
        <View style={{ gap: dp(10) }}>
            <Spinner line={progress ? stepLine(progress) : t.starting} />
            {progress?.loaded !== undefined ? <ProgressBar share={share} /> : null}
            {detail ? <Text style={{ color: colours.muted, fontSize: dp(14), fontVariant: ["tabular-nums"] }}>{detail}</Text> : null}
        </View>
    );
}

export function LoadingScreen({ model, state }: { model: AppModel; state: State }) {
    const dp = useDp();
    const { progress, error } = state.loading;
    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, justifyContent: "center", maxWidth: dp(560), gap: dp(18) }}>
                <Text style={{ color: colours.text, fontSize: dp(28), fontWeight: "600" }}>{t.loadingTitle}</Text>
                {error ? (
                    <>
                        <Text style={{ color: colours.danger, fontSize: dp(16), lineHeight: dp(24) }}>{error}</Text>
                        <View style={{ flexDirection: "row", gap: dp(12) }}>
                            <Button label={t.tryAgain} filled start onPress={() => void model.firstDownload()} />
                            <Button label={t.changeLogin} onPress={() => void model.signOut()} />
                        </View>
                    </>
                ) : (
                    <ProgressLines progress={progress ?? null} />
                )}
            </View>
        </Screen>
    );
}
