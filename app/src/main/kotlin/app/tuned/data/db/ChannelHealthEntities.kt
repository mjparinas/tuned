package app.tuned.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** What auto-tune learned about a live channel: when it last played or failed, and how sharp it was. */
@Entity(tableName = "channel_health")
data class ChannelHealthEntity(
    @PrimaryKey val itemId: Long,
    val okAt: Long,
    val deadAt: Long,
    val height: Int,
)

/** A live channel an event search found, with its health, for auto-tune to rank. */
data class TuneRow(
    val id: Long,
    val name: String,
    val groupName: String,
    val region: String?,
    val nameMatch: Boolean,
    val guideMatch: Boolean,
    val okAt: Long?,
    val deadAt: Long?,
    val height: Int?,
    /** Hidden in the lists (dead in a scan, or a "No event" placeholder); auto-tune still tries event channels. */
    val held: Boolean,
)

/**
 * What channel scans found for a live channel ([app.tuned.core.ChannelScan.Record]). [hidden] is stored, not worked
 * out, so the lists can leave hidden channels out in SQL.
 */
@Entity(tableName = "channel_scan")
data class ChannelScanEntity(
    @PrimaryKey val itemId: Long,
    val failures: Int,
    val firstFailAt: Long,
    val lastFailAt: Long,
    val lastOkAt: Long,
    val reason: String?,
    val kept: Boolean,
    val hidden: Boolean,
)

/** A channel a scan hid, with its name, for [ChannelScanDao.hiddenNames]. */
data class HiddenScanRow(
    val itemId: Long,
    val name: String,
    val groupName: String,
)

/** A row of the channel health report: the channel and what scans found. */
data class ScanReportRow(
    val itemId: Long,
    val name: String,
    val logo: String?,
    val groupName: String,
    val failures: Int,
    val lastFailAt: Long,
    val reason: String?,
    val hidden: Boolean,
)
