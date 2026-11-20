package com.tunedplayer.app.data.db

// SQL pieces the DAOs share: the profile in use and the filters every list applies.

/** The profile in use; 1 (the first profile) when none is set, as in a fresh test database. */
internal const val ME = "COALESCE((SELECT profileId FROM current_profile), 1)"

/** Kids profiles: only kids and family categories ([com.tunedplayer.app.core.KidsContent], keep the two in step). */
internal const val KIDS_ONLY_I =
    "AND NOT ('kids' IN (:hidden) AND NOT (" +
        "lower(i.groupName) GLOB '*kid*' OR " +
        "lower(i.groupName) GLOB '*child*' OR " +
        "lower(i.groupName) GLOB '*cartoon*' OR " +
        "lower(i.groupName) GLOB '*family*' OR " +
        "lower(i.groupName) GLOB '*junior*' OR " +
        "lower(i.groupName) GLOB '*disney*' OR " +
        "lower(i.groupName) GLOB '*nick*' OR " +
        "lower(i.groupName) GLOB '*baby*' OR " +
        "lower(i.groupName) GLOB '*animation*' OR " +
        "lower(i.groupName) GLOB '*boomerang*' OR " +
        "(i.kind = 0 AND (" +
        "i.searchName GLOB '*kid*' OR " +
        "i.searchName GLOB '*child*' OR " +
        "i.searchName GLOB '*cartoon*' OR " +
        "i.searchName GLOB '*junior*' OR " +
        "i.searchName GLOB '*disney*' OR " +
        "i.searchName GLOB '*nick*' OR " +
        "i.searchName GLOB '*baby*' OR " +
        "i.searchName GLOB '*boomerang*'" +
        "))))"

/** [KIDS_ONLY_I] for queries that don't name the table. */
internal const val KIDS_ONLY =
    "AND NOT ('kids' IN (:hidden) AND NOT (" +
        "lower(groupName) GLOB '*kid*' OR " +
        "lower(groupName) GLOB '*child*' OR " +
        "lower(groupName) GLOB '*cartoon*' OR " +
        "lower(groupName) GLOB '*family*' OR " +
        "lower(groupName) GLOB '*junior*' OR " +
        "lower(groupName) GLOB '*disney*' OR " +
        "lower(groupName) GLOB '*nick*' OR " +
        "lower(groupName) GLOB '*baby*' OR " +
        "lower(groupName) GLOB '*animation*' OR " +
        "lower(groupName) GLOB '*boomerang*' OR " +
        "(kind = 0 AND (" +
        "searchName GLOB '*kid*' OR " +
        "searchName GLOB '*child*' OR " +
        "searchName GLOB '*cartoon*' OR " +
        "searchName GLOB '*junior*' OR " +
        "searchName GLOB '*disney*' OR " +
        "searchName GLOB '*nick*' OR " +
        "searchName GLOB '*baby*' OR " +
        "searchName GLOB '*boomerang*'" +
        "))))"

/** [KIDS_ONLY_I] for the per-show table, which has groups but no channel names. */
internal const val KIDS_ONLY_S =
    "AND NOT ('kids' IN (:hidden) AND NOT (" +
        "lower(s.groupName) GLOB '*kid*' OR " +
        "lower(s.groupName) GLOB '*child*' OR " +
        "lower(s.groupName) GLOB '*cartoon*' OR " +
        "lower(s.groupName) GLOB '*family*' OR " +
        "lower(s.groupName) GLOB '*junior*' OR " +
        "lower(s.groupName) GLOB '*disney*' OR " +
        "lower(s.groupName) GLOB '*nick*' OR " +
        "lower(s.groupName) GLOB '*baby*' OR " +
        "lower(s.groupName) GLOB '*animation*' OR " +
        "lower(s.groupName) GLOB '*boomerang*'" +
        "))"

/** [KIDS_ONLY_S] for queries on the per-show table that don't name it. */
internal const val KIDS_ONLY_GROUPS =
    "AND NOT ('kids' IN (:hidden) AND NOT (" +
        "lower(groupName) GLOB '*kid*' OR " +
        "lower(groupName) GLOB '*child*' OR " +
        "lower(groupName) GLOB '*cartoon*' OR " +
        "lower(groupName) GLOB '*family*' OR " +
        "lower(groupName) GLOB '*junior*' OR " +
        "lower(groupName) GLOB '*disney*' OR " +
        "lower(groupName) GLOB '*nick*' OR " +
        "lower(groupName) GLOB '*baby*' OR " +
        "lower(groupName) GLOB '*animation*' OR " +
        "lower(groupName) GLOB '*boomerang*'" +
        "))"

/** Hide 24/7 ([com.tunedplayer.app.core.RoundTheClock]) for the per-show table, on the category and the show's name. */
internal const val ROUND_THE_CLOCK_S =
    "AND NOT ('247' IN (:hidden) " +
        "AND ((' ' || lower(s.groupName || ' ' || s.showName) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*' " +
        "OR (' ' || lower(s.groupName || ' ' || s.showName) || ' ') GLOB '*[^0-9]247[^0-9]*' " +
        "OR (' ' || lower(s.groupName || ' ' || s.showName) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))"

/** [ROUND_THE_CLOCK_S] for queries on the per-show table that don't name it. */
internal const val ROUND_THE_CLOCK_GROUPS =
    "AND NOT ('247' IN (:hidden) " +
        "AND ((' ' || lower(groupName || ' ' || showName) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*' " +
        "OR (' ' || lower(groupName || ' ' || showName) || ' ') GLOB '*[^0-9]247[^0-9]*' " +
        "OR (' ' || lower(groupName || ' ' || showName) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))"

/** What a Live TV row shows: the channel and what's on now. */
internal const val CHANNEL_COLUMNS =
    "i.id, i.name, i.logo, i.groupName, i.tvgId, i.number, p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop"

/**
 * What's on now: the latest programme started on the channel, found through the (channelId, start) index. One row per
 * channel by construction, so the lists need no GROUP BY, which made SQLite build and sort the whole list per page.
 */
internal const val NOW_JOIN =
    "LEFT JOIN programmes p ON p.rowId = (SELECT rowId FROM programmes WHERE channelId = i.tvgId AND start <= :now " +
        "AND stop > :now ORDER BY start DESC LIMIT 1)"

/** Hidden countries, groups, dead channels and empty event slots stay out; the group chip and the search narrow. */
internal const val CHANNEL_FILTER =
    "i.kind = 0 AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:0:' || i.groupName) NOT IN (:hidden) " +
        "AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) " +
        "AND NOT ('placeholder' IN (:hidden) AND ((' ' || i.searchName) GLOB '* no event*' " +
        "OR (' ' || i.searchName) GLOB '* no live event*')) " +
        "AND NOT ('247' IN (:hidden) AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*' " +
        "OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*' " +
        "OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*')) " +
        "AND (:adult OR i.adult = 0) $KIDS_ONLY_I AND (:group IS NULL OR i.groupName = :group) " +
        "AND (:query = '' OR i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match) OR i.tvgId IN (" +
        // An event search ("UFC") also finds the channels showing it now or within the hour.
        "SELECT channelId FROM programmes WHERE start > :now - 86400000 AND start < :now + 3600000 AND stop > :now " +
        "AND title LIKE '%' || :query || '%'))"

/**
 * Live TV's quick filters ([com.tunedplayer.app.ui.browse.LiveQuick]), on top of the chip chosen. `:topic` is a kind of channel's
 * full-text query ([com.tunedplayer.app.core.LiveTopic.fullText]) on channel names, or '' for any channel; the categories that
 * match it come in `:quick` as `g:<category>`, since a category has no full-text index. `:quick` also holds the country
 * tags to keep, or `any`, and `adult` for the Adult filter, which keeps only channels marked adult (and those show only
 * while unlocked, through `:adult`). A channel with no country found has none to match, so a country or language
 * leaves it out.
 */
internal const val LIVE_QUICK =
    "AND (:topic = '' OR ('g:' || i.groupName) IN (:quick) " +
        "OR i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :topic)) " +
        "AND ('any' IN (:quick) OR i.region IN (:quick)) " +
        "AND ('adult' NOT IN (:quick) OR i.adult = 1)"
