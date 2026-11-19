// POST /api/seats/deactivate { product, token, device, name, target }: frees target's seat, answers for device.
import { deactivate } from "../../lib/seats.mjs";
import { seatRequest } from "../../lib/http.mjs";

export function POST(request) {
    return seatRequest(request, (seats, ask, now) => deactivate(seats, ask.device, ask.target ?? ask.device, now));
}
