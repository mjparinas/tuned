// The QR code for the phone page, drawn with plain Views (dark runs on a white card) so it needs no SVG library.
import qrcode from "qrcode-generator";
import * as React from "react";
import { View } from "react-native";

export function Qr({ text, size }: { text: string; size: number }) {
    const rows = React.useMemo(() => {
        const qr = qrcode(0, "M");
        qr.addData(text);
        qr.make();
        const count = qr.getModuleCount();
        const out: { start: number; length: number }[][] = [];
        for (let r = 0; r < count; r++) {
            const runs: { start: number; length: number }[] = [];
            let c = 0;
            while (c < count) {
                if (!qr.isDark(r, c)) {
                    c++;
                    continue;
                }
                const start = c;
                while (c < count && qr.isDark(r, c)) c++;
                runs.push({ start, length: c - start });
            }
            out.push(runs);
        }
        return { out, count };
    }, [text]);
    const quiet = 2;
    const cell = size / (rows.count + quiet * 2);
    return (
        <View
            accessibilityLabel="QR code for the phone page"
            style={{ width: size, height: size, backgroundColor: "#ffffff", borderRadius: cell * 2 }}
        >
            {rows.out.map((runs, r) =>
                runs.map((run) => (
                    <View
                        key={`${r}:${run.start}`}
                        style={{
                            position: "absolute",
                            top: (r + quiet) * cell,
                            left: (run.start + quiet) * cell,
                            width: run.length * cell + 0.5,
                            height: cell + 0.5,
                            backgroundColor: "#0e0b1a",
                        }}
                    />
                )),
            )}
        </View>
    );
}
