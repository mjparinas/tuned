// A tiny shared state for the screens: a value, a way to change it, and a hook that redraws on change.
import { useEffect, useState } from "preact/hooks";

export interface Store<T> {
    get(): T;
    set(next: T | ((old: T) => T)): void;
    subscribe(listener: (value: T) => void): () => void;
}

export function store<T>(initial: T): Store<T> {
    let value = initial;
    const listeners = new Set<(value: T) => void>();
    return {
        get: () => value,
        set(next) {
            const v = typeof next === "function" ? (next as (old: T) => T)(value) : next;
            if (Object.is(v, value)) return;
            value = v;
            for (const l of [...listeners]) l(v);
        },
        subscribe(listener) {
            listeners.add(listener);
            return () => listeners.delete(listener);
        },
    };
}

export function useStore<T>(s: Store<T>): T {
    const [, redraw] = useState(0);
    useEffect(() => s.subscribe(() => redraw((n) => n + 1)), [s]);
    return s.get();
}
