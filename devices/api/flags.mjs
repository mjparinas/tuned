// GET /api/flags: see lib/admin/public.mjs.
import { endpoints } from "../lib/admin/public-wire.mjs";

export const GET = (request) => endpoints.flags(request);
