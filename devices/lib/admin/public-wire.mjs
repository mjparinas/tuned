// The real pieces behind the public endpoints.
import * as redis from "../redis.mjs";
import * as data from "./data.mjs";
import { publicApi } from "./public.mjs";
import { webAccount } from "../auth.mjs";

export const endpoints = publicApi({ data, redis, auth: { webAccount } });
