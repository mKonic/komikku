package mihon.buildlogic

import org.gradle.api.Project
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getCommitCount(): String {
    return runCommand("git rev-list --count HEAD")
    // return "1"
}

/**
 * `1.0.0` on a tag, `1.0.0-3-gabc1234` past one, `-dirty` on top of uncommitted work. The tag's
 * `v` is dropped: callers prepend their own, from tracker user agents to the release tag.
 *
 * Falls back to `unknown` rather than a plausible number: a version nobody can compare is better
 * than one that compares wrongly. A shallow clone has no tags, so CI must check out with
 * `fetch-depth: 0`.
 */
fun Project.getVersionName(): String {
    return runCommandOrNull("git describe --tags --always --dirty")?.removePrefix("v") ?: "unknown"
}

/**
 * Monotonic, and only ever says which of two builds is newer. Offset so it stays above the
 * hand-numbered codes this fork inherited.
 */
fun Project.getVersionCode(): Int {
    val count = runCommandOrNull("git rev-list --count HEAD")?.toIntOrNull() ?: return 0
    return 10000 + count
}

fun Project.getGitSha(): String {
    return runCommand("git rev-parse --short HEAD")
    // return "1"
}

private val BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * @param useLastCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return A formatted string representing the build time. The format used is defined by [BUILD_TIME_FORMATTER].
 */
fun Project.getBuildTime(useLastCommitTime: Boolean): String {
    return if (useLastCommitTime) {
        val epoch = runCommand("git log -1 --format=%ct").toLong()
        Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    } else {
        LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    }
}

private fun Project.runCommand(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}

private fun Project.runCommandOrNull(command: String): String? {
    return runCatching { runCommand(command) }.getOrNull()?.takeIf { it.isNotEmpty() }
}
