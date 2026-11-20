package com.tunedplayer.app.ui

import com.tunedplayer.app.data.AppSettings

/** "Who's watching?" opens first: there is more than one profile and Settings asks at every start. */
internal fun AppSettings.asksProfileAtStart(): Boolean = profileCount > 1 && askProfileAtStart

/**
 * A fresh launch goes straight into the last channel (Settings › Start on the last channel), except when "Who's
 * watching?" opens first: the channel would belong to whoever watched last, before the person picks a profile or
 * types its PIN, so a kids profile could open on a grown-up's channel.
 */
internal fun AppSettings.startsOnLastChannel(): Boolean = startOnLastChannel && !asksProfileAtStart()
