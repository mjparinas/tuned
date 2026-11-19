// POST /api/order/remove { order, remove }: frees one device's seat, or every seat ("all"), once every 30 days.
import { siteRequest } from "../../lib/site.mjs";

export function POST(request) {
    return siteRequest(request, true);
}
