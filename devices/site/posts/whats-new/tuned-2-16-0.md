---
title: What's new in Tuned 2.16.0
description: "Tuned 2.16.0, the IPTV player for Android and Android TV, is out. Live events from channel names when the guide is empty, Simkl history import, and a fuller Report a problem."
date: 2026-11-25
kind: release
version: 2.16.0
---

Tuned 2.16.0 is out: 3 new, 1 fixed.

## New

- Live events show up even when a pay-per-view or sports channel's guide is empty: Tuned reads the event and its start time from the channel's name, such as "PPV 7: UFC 312 10PM ET", and lists it with the rest of today's games and fights
- An event whose name gives no time is listed under Later today with Tune, since it may already be on
- Settings › Simkl › Import watched history marks the films and episodes you've watched on Simkl as watched in Tuned, for the profile in use, with each step shown as it goes
- Report a problem is wider, and Send to Tuned now sends the whole log, recent crash reports and your device's details along with what you wrote, so a fix needs no back and forth. It never sends your account's email; it only includes an email if you type one for a reply

## Fixed

- Built with the latest Android and Compose libraries
