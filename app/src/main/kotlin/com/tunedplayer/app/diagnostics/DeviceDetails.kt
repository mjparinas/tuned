package com.tunedplayer.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Display
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.data.db.offMain
import com.tunedplayer.app.work.TvHome
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone

/**
 * Everything about this device and install that helps find a problem, for the Report button on Logs (Melvin,
 * 2026-11-21: "automatically send all of the users logs, and all of their device information to me ... the more data
 * the better"). Plain "Label: value" lines in sections. It never holds a login: no playlist's server, username,
 * password or link, only how many playlists there are, of which kinds, and how big; and the whole text goes through
 * [AppLog.scrub] and has every saved login masked ([render]) before it leaves. A fact that can't be read says why
 * instead of stopping the rest.
 */
object DeviceDetails {
    private const val TAG = "Report"
    private const val MB = 1024L * 1024L
    private const val MIN_SECRET = 3
    private const val KBPS = 1000

    data class Section(
        val title: String,
        val facts: List<Pair<String, String>>,
    )

    /** All of it as text, logins masked. Reads files and the database, so call it off the main thread. */
    suspend fun collect(context: Context): String {
        val c = context.container
        val saved = attempt(TAG, "Couldn't read the saved playlists") { c.credentials.saved() }.getOrDefault(emptyList())
        val current = attempt(TAG, "Couldn't read the playlist in use") { c.credentials.load() }.getOrNull()
        val inUse = saved.indexOfFirst { current?.samePlaylist(it) == true }
        val secrets =
            saved.flatMap { listOf(it.password, it.username, it.playlistUrl, it.guideUrl) + it.extraGuides }
        val sections =
            listOf(
                app(context),
                device(context),
                screen(context),
                memory(context),
                storage(context),
                network(context),
                power(context),
                region(context),
                account(context),
                library(context, saved.map { it.kind.name }, inUse),
            )
        return render(sections, secrets)
    }

    /** The sections as text, with [secrets] (and anything [AppLog.scrub] recognises) masked. Pure, so it's tested. */
    fun render(
        sections: List<Section>,
        secrets: Collection<String>,
    ): String {
        val text =
            buildString {
                for (section in sections) {
                    appendLine("[${section.title}]")
                    for ((label, value) in section.facts) appendLine("$label: $value")
                    appendLine()
                }
            }.trimEnd()
        var out = AppLog.scrub(text)
        // As written and as it appears inside a link; longest first, so a password that holds the username goes whole.
        val masked = secrets.flatMap { listOf(it, URLEncoder.encode(it, "UTF-8")) }.filter { it.length >= MIN_SECRET }
        for (secret in masked.distinct().sortedByDescending { it.length }) {
            out = out.replace(secret, MASK)
        }
        return out
    }

    private const val MASK = "•••"

    private class Facts {
        val list = mutableListOf<Pair<String, String>>()

        inline fun add(
            label: String,
            value: () -> Any?,
        ) {
            val read = attempt(TAG, "Couldn't read $label for the report") { value() }
            val shown = read.getOrNull()?.toString()
            list += label to (shown ?: read.exceptionOrNull()?.let { "unknown (${it.javaClass.simpleName})" } ?: "none")
        }
    }

    private inline fun section(
        title: String,
        fill: Facts.() -> Unit,
    ): Section = Section(title, Facts().apply(fill).list)

    private fun app(context: Context) = section("Tuned") {
        add("Version") { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
        add("Build") { BuildConfig.GIT_COMMIT.ifEmpty { "local" } }
        add("From") { if (BuildConfig.GITHUB_UPDATES) "GitHub" else "store" }
        add("Debug") { BuildConfig.DEBUG }
        add("Installed by") { installer(context) ?: "unknown (side-loaded)" }
        add("First installed") { date(packageInfo(context).firstInstallTime) }
        add("Last updated") { date(packageInfo(context).lastUpdateTime) }
        add("Running for") { minutes(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) }
        add("Premium") { context.container.premium.unlocked.value }
        add("Crash and freeze reports saved") { CrashReporter.reports(context).size }
    }

    private fun device(context: Context) = section("Device") {
        add("Maker") { Build.MANUFACTURER }
        add("Brand") { Build.BRAND }
        add("Model") { Build.MODEL }
        add("Device") { Build.DEVICE }
        add("Product") { Build.PRODUCT }
        add("Hardware") { Build.HARDWARE }
        add("Board") { Build.BOARD }
        add("Chip") { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null }
        add("CPU") { "${Build.SUPPORTED_ABIS.joinToString()} · ${Runtime.getRuntime().availableProcessors()} cores" }
        add("Kind") { kind(context) }
        add("Android") { "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
        add("Security patch") { Build.VERSION.SECURITY_PATCH }
        add("Android build") { Build.DISPLAY }
        add("Fingerprint") { Build.FINGERPRINT }
        add("WebView") { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }
        add("Touch screen") { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    }

    @Suppress("DEPRECATION") // HdrCapabilities is the way that works back to Android 8.0
    private fun screen(context: Context) = section("Screen") {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        add("Pixels") { "${metrics.widthPixels} × ${metrics.heightPixels}" }
        add("Size in dp") { "${config.screenWidthDp} × ${config.screenHeightDp} (smallest ${config.smallestScreenWidthDp})" }
        add("Density") { "${metrics.densityDpi} dpi (×${metrics.density})" }
        add("Font scale") { config.fontScale }
        add("Orientation") { if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait" }
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        add("Refresh rate") { display?.refreshRate?.let { "%.0f Hz".format(Locale.ROOT, it) } }
        add("Display modes") {
            display?.supportedModes?.joinToString { mode ->
                "${mode.physicalWidth}×${mode.physicalHeight}@${"%.0f".format(Locale.ROOT, mode.refreshRate)}"
            }
        }
        add("HDR") { display?.hdrCapabilities?.supportedHdrTypes?.joinToString { hdr(it) }?.ifEmpty { "none" } }
    }

    private fun memory(context: Context) = section("Memory") {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        add("Total") { mb(info.totalMem) }
        add("Free") { mb(info.availMem) }
        add("Low on memory") { info.lowMemory }
        add("Low-memory device") { am?.isLowRamDevice }
        add("App limit") { am?.let { "${it.memoryClass} MB (large ${it.largeMemoryClass} MB)" } }
        add("App using") { "${mb(runtime.totalMemory() - runtime.freeMemory())} of ${mb(runtime.maxMemory())}" }
    }

    private fun storage(context: Context) = section("Storage") {
        val stat = StatFs(context.filesDir.path)
        add("Free") { "${mb(stat.availableBytes)} of ${mb(stat.totalBytes)}" }
        add("Database") { mb(size(context.getDatabasePath("tuned.db").parentFile)) }
        add("App files") { mb(size(context.filesDir)) }
        add("Cache") { mb(size(context.cacheDir)) }
        add("Downloads folder") { context.getExternalFilesDir(null)?.let { mb(size(it)) } }
    }

    private fun network(context: Context) = section("Network") {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        add("Connected") { caps != null }
        add("Type") {
            caps?.let {
                listOfNotNull(
                    "Wi-Fi".takeIf { _ -> it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                    "Ethernet".takeIf { _ -> it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                    "mobile data".takeIf { _ -> it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    "VPN".takeIf { _ -> it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                    "Bluetooth".takeIf { _ -> it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) },
                ).joinToString().ifEmpty { "other" }
            }
        }
        add("Internet checked") { caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        add("Metered") { cm?.isActiveNetworkMetered }
        add("Speed estimate") {
            caps?.let { "${it.linkDownstreamBandwidthKbps / KBPS} Mbps down, ${it.linkUpstreamBandwidthKbps / KBPS} Mbps up" }
        }
        add("Private DNS") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm?.activeNetwork?.let { cm.getLinkProperties(it)?.isPrivateDnsActive }
            } else {
                null
            }
        }
    }

    private fun power(context: Context) = section("Power") {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        add("Battery") { if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "none (mains)" }
        add("Plugged in") { battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } }
        add("Battery saver") { power?.isPowerSaveMode }
        add("Free of battery limits") { power?.isIgnoringBatteryOptimizations(context.packageName) }
    }

    private fun region(context: Context) = section("Language and time") {
        add("Language") { Locale.getDefault().toLanguageTag() }
        add("Device languages") { context.resources.configuration.locales.toLanguageTags() }
        add("Time zone") { TimeZone.getDefault().id }
        add("24-hour clock") { DateFormat.is24HourFormat(context) }
        add("Time now") { date(System.currentTimeMillis()) }
    }

    private suspend fun account(context: Context): Section {
        val c = context.container
        val install = attempt(TAG, "Couldn't read the install id") { c.usage.idIfOn() }.getOrNull()
        return section("Account") {
            add("Tuned account") { if (c.tunedAccount.signedIn.value) c.tunedAccount.email.value ?: "signed in" else "signed out" }
            add("Install id") { install ?: "usage stats off" }
        }
    }

    private suspend fun library(
        context: Context,
        kinds: List<String>,
        inUse: Int,
    ): Section {
        val db = context.container.db
        val counts =
            attempt(TAG, "Couldn't count the playlist for the report") {
                offMain {
                    db.openHelper.readableDatabase.query("SELECT kind, COUNT(*) FROM items GROUP BY kind").use { cursor ->
                        buildMap { while (cursor.moveToNext()) put(cursor.getInt(0), cursor.getLong(1)) }
                    }
                }
            }.getOrNull()
        val programmes = attempt(TAG, "Couldn't count the guide for the report") { db.programmes().count() }.getOrNull()
        val downloads = attempt(TAG, "Couldn't count downloads for the report") { db.downloads().count() }.getOrNull()
        return section("Playlists") {
            add("Saved") { kinds.size }
            add("Kinds") { kinds.joinToString().ifEmpty { "none" } }
            add("In use") { if (inUse >= 0) "number ${inUse + 1}, ${kinds[inUse]}" else "none" }
            add("Live channels") { counts?.get(ContentKind.LIVE.code) ?: 0 }
            add("Films") { counts?.get(ContentKind.MOVIE.code) ?: 0 }
            add("Episodes") { counts?.get(ContentKind.SERIES.code) ?: 0 }
            add("Guide entries") { programmes }
            add("Downloads") { downloads }
        }
    }

    private fun kind(context: Context): String = when {
        TvHome.isTv(context) -> "TV"
        context.resources.configuration.smallestScreenWidthDp >= TABLET_DP -> "tablet"
        else -> "phone"
    }

    private const val TABLET_DP = 600

    @Suppress("DEPRECATION") // the Int flags overload is the one Android 8.0 has
    private fun packageInfo(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0)

    private fun installer(context: Context): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION") // getInstallSourceInfo is Android 11 and later
        context.packageManager.getInstallerPackageName(context.packageName)
    }

    private fun hdr(type: Int): String = when (type) {
        1 -> "Dolby Vision"
        2 -> "HDR10"
        3 -> "HLG"
        4 -> "HDR10+"
        else -> "type $type"
    }

    private fun size(dir: File?): Long = dir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun mb(bytes: Long): String = "${bytes / MB} MB"

    private fun minutes(ms: Long): String = "${ms / 60_000} min"

    private fun date(ms: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ms))
}
