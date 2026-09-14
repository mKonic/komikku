package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.AppUpdatePolicy
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The rate limit around the update check: how long it waits, and when it has to ask anyway.
 *
 * The limit is there to spare the network, and the cases below are the ones where it was instead
 * hiding an update the reader had already been told about.
 */
class GetApplicationReleaseGateTest {

    /**
     * Stand-ins for what is installed and what the repository is offering. Only their order matters:
     * the interactor compares whatever strings it is handed, so nothing here follows the app's own
     * version line.
     */
    private val installed = "1.0.0"
    private val newer = "v1.1.0"
    private val newerStill = "v1.2.0"

    private class FakeService(private val releases: List<Release>) : ReleaseService {
        var calls = 0
        override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? = releases.firstOrNull()
        override suspend fun releaseNotes(arguments: GetApplicationRelease.Arguments): List<Release> {
            calls++
            return releases
        }
    }

    private fun release(version: String) = Release(
        version = version,
        info = "",
        releaseLink = "",
        downloadLink = "",
    )

    private fun arguments(versionName: String, forceCheck: Boolean = false) = GetApplicationRelease.Arguments(
        isFoss = false,
        isPreview = false,
        commitCount = 0,
        versionName = versionName,
        repository = "owner/repo",
        forceCheck = forceCheck,
    )

    private fun store(
        checkedAt: Instant? = null,
        found: String = "",
        skipped: String = "",
        intervalDays: Int = AppUpdatePolicy.CHECK_INTERVAL_DEFAULT,
    ) = InMemoryPreferenceStore(
        sequenceOf(
            InMemoryPreferenceStore.InMemoryPreference(
                key = Preference.appStateKey("last_app_check"),
                data = checkedAt?.toEpochMilli() ?: 0L,
                defaultValue = 0L,
            ),
            InMemoryPreferenceStore.InMemoryPreference(
                key = Preference.appStateKey("last_app_found"),
                data = found,
                defaultValue = "",
            ),
            InMemoryPreferenceStore.InMemoryPreference(
                key = Preference.appStateKey("skipped_app_version"),
                data = skipped,
                defaultValue = "",
            ),
            InMemoryPreferenceStore.InMemoryPreference(
                key = AppUpdatePolicy.CHECK_INTERVAL_KEY,
                data = intervalDays,
                defaultValue = AppUpdatePolicy.CHECK_INTERVAL_DEFAULT,
            ),
        ),
    )

    @Test
    fun `an update found once is still reported while it is not installed`() = runTest {
        // What made the prompt vanish from launch: checking by hand set the timestamp, and every
        // launch for the next two days then answered "nothing new" although the update was waiting.
        val releases = listOf(release(newer))
        val service = FakeService(releases)
        val interactor = GetApplicationRelease(service, store(checkedAt = Instant.now(), found = newer))

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `an up to date app is not asked again inside the window`() = runTest {
        val service = FakeService(emptyList())
        val interactor = GetApplicationRelease(service, store(checkedAt = Instant.now()))

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 0
    }

    @Test
    fun `a check that finds nothing still counts as a check`() = runTest {
        // Otherwise the timestamp is only ever written when there is an update, so an app that is up
        // to date asks again on every single launch, the opposite of what the limit is for.
        val service = FakeService(emptyList())
        val interactor = GetApplicationRelease(service, store())

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 1

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 1
    }

    @Test
    fun `installing the update lets the window apply again`() = runTest {
        val service = FakeService(emptyList())
        val interactor = GetApplicationRelease(service, store(checkedAt = Instant.now(), found = newer))

        // Now running the version that was pending, so there is nothing left to re-announce.
        interactor.await(arguments(newer)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 0
    }

    @Test
    fun `a skipped version is not raised again by the app itself`() = runTest {
        val service = FakeService(listOf(release(newer)))
        val interactor = GetApplicationRelease(service, store(skipped = newer))

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `asking by hand still reports a skipped version`() = runTest {
        val releases = listOf(release(newer))
        val service = FakeService(releases)
        val interactor = GetApplicationRelease(service, store(skipped = newer))

        interactor.await(arguments(installed, forceCheck = true)) shouldBe
            GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `a release after the skipped one is offered`() = runTest {
        val releases = listOf(release(newerStill))
        val service = FakeService(releases)
        val interactor = GetApplicationRelease(service, store(skipped = newer))

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `a skipped version does not keep the check awake`() = runTest {
        // Without this the re-offer above would spend a request on every launch, for the one release
        // the reader has said they do not want.
        val service = FakeService(listOf(release(newer)))
        val interactor = GetApplicationRelease(
            service,
            store(checkedAt = Instant.now(), found = newer, skipped = newer),
        )

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 0
    }

    @Test
    fun `an interval of zero checks on every launch`() = runTest {
        val service = FakeService(emptyList())
        val interactor = GetApplicationRelease(service, store(checkedAt = Instant.now(), intervalDays = 0))

        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        interactor.await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 2
    }

    @Test
    fun `a longer interval holds the check back past the default window`() = runTest {
        val threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS)
        val service = FakeService(emptyList())

        // The default would have asked by now.
        GetApplicationRelease(service, store(checkedAt = threeDaysAgo))
            .await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 1

        GetApplicationRelease(service, store(checkedAt = threeDaysAgo, intervalDays = 7))
            .await(arguments(installed)) shouldBe GetApplicationRelease.Result.NoNewUpdate
        service.calls shouldBe 1
    }
}
