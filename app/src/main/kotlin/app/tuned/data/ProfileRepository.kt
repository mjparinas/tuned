package app.tuned.data

import android.os.SystemClock
import app.tuned.core.PinHash
import app.tuned.core.PinThrottle
import app.tuned.core.ProfileLock
import app.tuned.core.ProfileRules
import app.tuned.data.db.CurrentProfileEntity
import app.tuned.data.db.ProfileEntity
import app.tuned.data.db.Profiles
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Who's watching (docs/decisions/profiles.md). Each profile keeps its own favourites, history, watch later and seen
 * episodes; everything else (the playlist, settings, downloads) is shared. The profile in use lives in the database,
 * where every list query reads it, and is mirrored into [SettingsRepository] so the first frame knows it at once.
 */
class ProfileRepository(
    private val db: TunedDatabase,
    private val settings: SettingsRepository,
) {
    private val dao get() = db.profiles()

    /** One throttle for every PIN on the device (profile and parental), so swapping between them doesn't reset it. */
    val throttle = PinThrottle(clock = SystemClock::elapsedRealtime)

    val profiles: Flow<List<ProfileEntity>> get() = dao.all()

    val current: Flow<ProfileEntity?> get() = dao.current()

    /** Puts the mirror right, in case the app stopped between a switch and its mirror. Runs once at start. */
    suspend fun sync() = mirror()

    /** What stands between the profile in use and [target]. */
    suspend fun lockFor(target: ProfileEntity): ProfileLock {
        val from = dao.currentNow()
        if (from?.id == target.id) return ProfileLock.NONE
        return ProfileRules.lockFor(
            fromKids = from?.kids ?: false,
            toKids = target.kids,
            toHasPin = target.pinHash != null,
            parentalPinSet = settings.hasAdultPin.first(),
        )
    }

    suspend fun switchTo(id: Long) {
        dao.setCurrent(CurrentProfileEntity(profileId = id))
        settings.lockAdult() // an adult unlock belongs to the person who entered the PIN
        mirror()
    }

    suspend fun create(
        name: String,
        colour: Int,
        kids: Boolean,
        avatar: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val id =
            dao.insert(
                ProfileEntity(name = name.trim(), colour = colour, kids = kids, pinHash = null, createdAt = now, avatar = avatar),
            )
        mirror()
        return id
    }

    suspend fun save(profile: ProfileEntity) {
        dao.update(profile.copy(name = profile.name.trim()))
        mirror()
    }

    /** Locks [id] with [pin], or unlocks it when [pin] is null. */
    suspend fun setPin(
        id: Long,
        pin: String?,
    ) {
        val profile = dao.get(id) ?: return
        val hash = pin?.let { withContext(Dispatchers.Default) { PinHash.create(it) } }
        dao.update(profile.copy(pinHash = hash))
    }

    /** Checks [pin] against [id]'s own PIN, through the shared throttle. */
    suspend fun checkPin(
        id: Long,
        pin: String,
    ): PinThrottle.Result {
        if (throttle.waiting()) return PinThrottle.Result.TOO_MANY
        val stored = dao.get(id)?.pinHash
        val ok = stored != null && withContext(Dispatchers.Default) { PinHash.verify(pin, stored) }
        return throttle.record(ok)
    }

    /** Checks the parental PIN through the shared throttle, without unlocking adult categories. */
    suspend fun checkParentalPin(pin: String): PinThrottle.Result {
        if (throttle.waiting()) return PinThrottle.Result.TOO_MANY
        return throttle.record(settings.checkAdultPin(pin))
    }

    /** Removes [id] and everything it kept. The profile in use, and the last one, can't be removed. */
    suspend fun delete(id: Long) {
        val all = dao.allNow()
        if (all.size <= 1 || dao.currentNow()?.id == id) return
        dao.delete(id)
        mirror()
    }

    private suspend fun mirror() {
        val all = dao.allNow()
        val now = dao.currentNow() ?: all.firstOrNull()
        settings.mirrorProfile(now?.id ?: Profiles.FIRST, now?.kids ?: false, all.size.coerceAtLeast(1))
    }
}
