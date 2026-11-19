// POST /api/codes/redeem: see lib/admin/public.mjs.
import { endpoints } from "../../lib/admin/public-wire.mjs";

export const POST = (request) => endpoints.redeem(request);
