package app.tuned.player

import app.tuned.core.ContentKind
import app.tuned.data.AppSettings
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.TunedDatabase
import app.tuned.data.hiddenKeys

/** Live: the other channels in the same category, so next/previous flips channels. Shows: the show's episodes. */
suspend fun playerSiblings(
    db: TunedDatabase,
    settings: AppSettings,
    item: ItemEntity,
): List<ItemEntity> {
    val items = db.items()
    return when (ContentKind.fromCode(item.kind)) {
        ContentKind.LIVE -> items.channelsInGroup(item.groupName, settings.hiddenKeys(), settings.showAdult)
        ContentKind.SERIES -> item.showKey?.let { items.episodesNow(it) }.orEmpty()
        ContentKind.MOVIE -> emptyList()
    }.let { siblings ->
        // A channel its category hides (one the scan found dead, a 24/7 channel, a hidden country) can still be opened
        // from Favourites, Search, the guide or the widget. Without it in the queue the player fell back to the first
        // channel of the category and played something else.
        if (siblings.any { it.id == item.id }) siblings else listOf(item) + siblings
    }
}
