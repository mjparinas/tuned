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
    }.ifEmpty { listOf(item) }
}
