// The real pieces behind the admin's endpoints, loaded only when a request comes in.
import * as redis from "../redis.mjs";
import * as data from "./data.mjs";
import { adminApi } from "./api.mjs";
import { adminSessions } from "./session.mjs";
import { systemHealth } from "./health.mjs";
import { resendMailer } from "./automations.mjs";
import { account } from "../account-store.mjs";
import { googleStart, signInReady, webAccount } from "../auth.mjs";

export const handle = adminApi({
    data,
    redis,
    env: process.env,
    sessions: adminSessions(redis),
    auth: { webAccount, account, googleStart, signInReady },
    system: systemHealth({ data, redis }),
    mail: resendMailer(),
});
