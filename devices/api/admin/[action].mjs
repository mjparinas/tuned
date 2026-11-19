// /api/admin/<action>: the admin panel's endpoints (lib/admin/api.mjs).
import { handle } from "../../lib/admin/wire.mjs";

const action = (request) => new URL(request.url).pathname.split("/").filter(Boolean)[2] ?? "";

export const GET = (request) => handle(request, action(request));
export const POST = (request) => handle(request, action(request));
