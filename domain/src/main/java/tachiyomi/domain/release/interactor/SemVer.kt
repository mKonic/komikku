package tachiyomi.domain.release.interactor

import java.math.BigInteger

/**
 * A version as semver.org orders it. Parses release tags (`v1.4.0`, `v1.5.0-rc.1`, `v1.4.0+exp`) and the
 * app's own git-describe names (`1.4.0-3-gabc1234-dirty`), which count as the tag they were built on.
 * Every number may be any size.
 */
internal data class SemVer(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val preRelease: List<String>,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A version without pre-release identifiers ranks above every pre-release of it.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.coerceAtMost(1).compareTo(preRelease.size.coerceAtMost(1))
        }
        for ((a, b) in preRelease.zip(other.preRelease)) {
            val aNumeric = a.all(Char::isDigit)
            val bNumeric = b.all(Char::isDigit)
            val order = when {
                aNumeric && bNumeric -> a.toBigInteger().compareTo(b.toBigInteger())
                // Numeric identifiers rank below alphanumeric ones.
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        private val describeSuffix = Regex("""-\d+-g[0-9a-f]+(-dirty)?$""")
        private val grammar = Regex(
            """^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        fun parse(version: String): SemVer? {
            val base = version.trim().replace(describeSuffix, "").removeSuffix("-dirty")
            val (major, minor, patch, preRelease) = grammar.matchEntire(base)?.destructured ?: return null
            return SemVer(
                major.toBigInteger(),
                minor.toBigInteger(),
                patch.toBigInteger(),
                if (preRelease.isEmpty()) emptyList() else preRelease.split('.'),
            )
        }
    }
}
