// Shown only when the app link didn't open Tuned (a build whose signing key isn't in assetlinks.json yet): hands
// the one-time code to the app through its own scheme instead.
const code = new URLSearchParams(location.search).get("code") ?? "";
document.getElementById("back").href = `tuned://signed-in?code=${encodeURIComponent(code)}`;
