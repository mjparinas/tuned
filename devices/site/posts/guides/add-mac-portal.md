---
title: How to add a MAC portal (Stalker) playlist to Tuned
description: Sign in the way a MAG set-top box does, with the portal address and the MAC address your provider registered.
date: 2026-11-19
kind: guide
---

Some providers don't hand out a username and password. Instead they register a **MAC address** and give you a **portal address**. These are Stalker or Ministra portals, made for MAG set-top boxes, and Tuned can sign in to them the same way a MAG box does.

## What you need

- The **portal address**, usually ending in `/c/`, such as `http://portal.example.com/c/`.
- The **MAC address** your provider set up for you, such as `00:1A:79:12:34:56`. Type it any way you like: with colons, dashes or none.

## Add the portal

1. On Tuned's first screen, pick **MAC portal**. (Or **Settings › Playlists and account › Playlists › Add a playlist**.)
2. Enter the **Portal address** and the **MAC address**.
3. Add a **guide (XMLTV) link** only if you'd rather use another guide; leave it blank to use the portal's own.
4. Give it a name if you like, and tap **Add playlist**.

<img src="/blog/img/guides/portal-form.webp" alt="The MAC portal form with portal address, MAC address, an optional name and an optional guide link" width="270" height="586" loading="lazy">

Tuned shakes hands with the portal and sends it a MAG box's details. If the portal doesn't know your MAC, it refuses, and the form tells you so.

## What you get

- **Live TV** with every channel the portal lists, sorted into groups and countries like any playlist.
- **The TV guide** from the portal, up to a week ahead.

Films and shows from portals aren't included for now. Portals list them about fourteen at a time, so a large library would take thousands of requests before anything showed. If your provider also offers an Xtream Codes login, [use that](/blog/guides/add-xtream-codes-playlist/) to get films and shows too.

## How playing works

A portal hands out a fresh, short-lived link each time a channel opens. Tuned asks for it just before the channel plays, and asks again once if the portal stops accepting it, so you never see an expired link. Pause and rewind, casting and channel scans all ask the same way.

## Your MAC is kept like a password

It's stored encrypted with the rest of your login, hidden in the app's logs, and forgotten when you sign out.
