// POST /api/pv: see lib/admin/public.mjs.
import { endpoints } from "../lib/admin/public-wire.mjs";

export const POST = (request) => endpoints.pv(request);
