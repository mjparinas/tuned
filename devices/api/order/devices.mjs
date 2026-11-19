// POST /api/order/devices { order }: the devices using the purchase with this Google Play order number.
import { siteRequest } from "../../lib/site.mjs";

export function POST(request) {
    return siteRequest(request, false);
}
