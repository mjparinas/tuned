---
title: Provider errors in Tuned and what they mean
seoTitle: "IPTV provider errors in Tuned and what they mean"
description: Sign-in refused, channels won't load, a stream won't play: what each message means and how to fix it.
date: 2026-11-19
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/provider-errors.jpg" alt="Tuned's first download naming each numbered step, beside its Logs page" width="1200" height="760"></figure>

Tuned plays what your provider serves, so most errors start on the provider's side. Here's what each message means and what to try. Tuned never shows your password in an error.

## "The server didn't accept that username and password."

The server answered, but said no.

- Check for a space at the end, and capital letters. Pasting is safer than typing.
- On a TV, the password shows as you type, so you can check it. Or [sign in with your phone](/blog/guides/sign-in-on-tv-with-phone/).
- Your subscription may have ended. Your provider can tell you.

## "Something went wrong while signing in."

Tuned couldn't reach the server, or got an answer it didn't understand.

- Check the server includes `http://` and the port, like `http://example.com:8080`.
- Try opening the server address in a browser on the same network.
- If Tuned says the device's date and time look wrong, fix them in Android's settings (Date and time). A wrong date makes secure connections fail.

## "Couldn't load your channels"

The first download failed. Tap **Change playlist** to fix the login or link, or try again. A few common causes:

- **The server blocks the playlist download.** Some providers turn it off. On an Xtream login, Tuned then reads the same channels through the server's other interface by itself, so this usually fixes itself.
- **The server only answers certain players.** Change the [user agent](/blog/guides/user-agent/).
- **The link expired.** Some M3U links carry a token that runs out. Ask your provider for a new one.

When a later refresh fails, nothing is lost: Tuned keeps the last good playlist and guide. **Settings › Guide and refresh** shows "Last refresh failed" with the reason.

## "Can't play this stream"

The channel or film didn't start.

- **One channel:** that channel may be down. Tuned tries its backup copies first if it has any. Try another channel; if others play, it's that one.
- **Every channel:** another device may be using your account's connections. Close it and try again.
- **Some channels only:** try another [user agent](/blog/guides/user-agent/).

For live events, **Tune** on the Live Events page tries channels showing the event one at a time and plays the first that works.

## "No sound"

The stream's sound is in a format this device can't play, such as Dolby Digital on some phones. Tuned picks another sound track when the stream has one; when it doesn't, the message names the format.

## Too many dead channels?

**Settings › When a channel fails › Channel health › Scan channels** checks every channel, one at a time, and hides the ones that don't work. It tells "the channel is down" apart from "the server is down" and "your internet is down", so a bad moment doesn't hide good channels.

## Reporting a problem

**Settings › Updates and advanced › Report a problem** gathers the app version, your device, the latest crash report and recent log lines into one message, and opens the share sheet so you choose where it goes. Logins are removed from the log before it's written.

See also [IPTV not working? A 10-step fix list](/blog/guides/iptv-not-working/), [fixing buffering](/blog/guides/buffering/) and [the TV guide is empty](/blog/guides/blank-tv-guide/).
