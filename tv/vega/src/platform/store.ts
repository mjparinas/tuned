// The app's store on the stick: Amazon's AsyncStorage, kept in the app's private /data, which no other app can read
// and which survives reboots and updates. There's no keystore on Vega, so the login is as private as the app's own
// folder; it's never logged and signing out removes it (docs/decisions/vega.md).
import AsyncStorage from "@amazon-devices/react-native-async-storage__async-storage";
import type { Store } from "../core/storage";

export const deviceStore: Store = {
    get: (key) => AsyncStorage.getItem(key),
    set: (key, value) => AsyncStorage.setItem(key, value),
    remove: (keys) => AsyncStorage.multiRemove(keys),
    keys: async () => [...(await AsyncStorage.getAllKeys())],
};
