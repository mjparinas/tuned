// POST /api/seats/activate { product, token, device, name }: takes a seat for this device or answers "full".
import { activate } from "../../lib/seats.mjs";
import { seatRequest } from "../../lib/http.mjs";

export function POST(request) {
    return seatRequest(request, (seats, ask, now) => activate(seats, ask.device, ask.name, now));
}
