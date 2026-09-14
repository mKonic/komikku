package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.AppUpdatePolicy
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    /**
     * KMK: the newest version a check has found, so the rate limit below can tell "nothing new since
     * last time" apart from "an update is sitting there and this reader has not installed it yet".
     */
    private val lastFound: Preference<String> by lazy {
        preferenceStore.getString(Preference.appStateKey("last_app_found"), "")
    }

    /** KMK: days to leave between checks, the reader's to set. Zero checks on every launch. */
    private val checkInterval: Preference<Int> by lazy {
        preferenceStore.getInt(AppUpdatePolicy.CHECK_INTERVAL_KEY, AppUpdatePolicy.CHECK_INTERVAL_DEFAULT)
    }

    /** KMK: a version the reader has turned down for good, so only this one stops being offered. */
    private val skipped: Preference<String> by lazy {
        preferenceStore.getString(Preference.appStateKey("skipped_app_version"), "")
    }

    /** KMK: stop offering [version] on its own. A later release, or a manual check, still reports. */
    fun skip(version: String) = skipped.set(version)

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()
        val skippedVersion = skipped.get()

        // Limit checks to once every 3 days at most
        // KMK: an interval of zero leaves the next check time at the last one, which is never in the future
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get())
            .plus(checkInterval.get().toLong(), ChronoUnit.DAYS)
        // KMK: the limit is there to spare the network, not to hide an update that is already known
        // about. Asking again costs one request and is the only way the prompt comes back at launch
        // for someone who has not installed it yet; without this, checking by hand re-arms two days
        // of silence every time.
        // KMK: a skipped version is not worth spending a request on either, or every launch would
        // ask again about the one release the reader said they did not want.
        val pending = lastFound.get().takeIf { it.isNotEmpty() && it != skippedVersion }?.let { found ->
            isNewVersion(arguments.isPreview, arguments.commitCount, arguments.versionName, found)
        } ?: false
        if (!arguments.forceCheck && !pending && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        // KMK -->
        val releases = service.releaseNotes(arguments)
            .filter {
                !it.preRelease &&
                    isNewVersion(
                        arguments.isPreview,
                        arguments.commitCount,
                        arguments.versionName,
                        it.version,
                    )
            }

        val latest = releases.getLatest()
        // KMK <--

        // KMK: before the early return below, so a check that finds nothing still counts as a check.
        // Left until after the request, it meant an up to date app asked again on every single launch.
        lastChecked.set(now.toEpochMilli())
        lastFound.set(latest?.version.orEmpty())

        if (latest == null) return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            isPreview = arguments.isPreview,
            commitCount = arguments.commitCount,
            versionName = arguments.versionName,
            versionTag = latest.version,
        )
        return when {
            !isNewVersion -> Result.NoNewUpdate
            // KMK: turned down for good, so the app stops raising it by itself. Asking still answers
            // honestly, and a release after this one is a different version and is offered again.
            !arguments.forceCheck && latest.version == skippedVersion -> Result.NoNewUpdate
            else -> Result.NewUpdate(latest)
        }
    }

    // KMK -->
    suspend fun awaitReleaseNotes(arguments: Arguments): Result {
        val releases = service.releaseNotes(arguments)
            .filter { !it.preRelease }

        val latest = releases.getLatest() ?: return Result.NoNewUpdate
        return Result.NewUpdate(latest)
    }
    // KMK <--

    /**
     * [isPreview] is if current version is Preview (beta) build
     *
     * [versionTag] is the version of new release
     *
     * Release (stable) version will compare with current's [versionName] ("v0.1.2")
     *
     * Preview (beta) version will compare with current's [commitCount] ("r1234")
     */
    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        return if (isPreview) {
            // Preview builds: based on releases in "komikku-app/komikku-preview" repo
            // tagged as something like "r1234"
            versionTag.replace("[^\\d.]".toRegex(), "").toInt() > commitCount
        } else {
            // Release builds: tagged as semver, "v1.4.0" or "v1.5.0-rc.1".
            // KMK --> semver order, so a pre-release ranks below its release and a number can be any size
            val new = SemVer.parse(versionTag) ?: return false
            val current = SemVer.parse(versionName) ?: return false
            new > current
            // KMK <--
        }
    }

    data class Arguments(
        val isFoss: Boolean,
        /** If current version is Preview (beta) build */
        val isPreview: Boolean,
        /** Commit count of current version */
        val commitCount: Int,
        /** Current version name, could be version tag (v0.1.2) or commit count (r1234) */
        val versionName: String,
        /** Repository name */
        val repository: String,
        /** Force check for new update */
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}

// KMK --.
internal fun List<Release>.getLatest(): Release? {
    return firstOrNull()
        ?.copy(
            info = joinToString("\r-----\r") {
                "## ${it.version}\r\r" +
                    it.info
            },
        )
}
// KMK <--
