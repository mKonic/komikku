package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private val TOKEN_LIFETIME = 365.days.inWholeMilliseconds
private val EXPIRY_MARGIN = 1.minutes.inWholeMilliseconds

/**
 * Anilist hands out a token rather than an expiry, so the lifetime is assumed. Both fields are
 * milliseconds; [expires] used to be multiplied by 1000 on load, which put every expiry tens of
 * thousands of years out and meant an expired token was only ever noticed as a 401.
 */
@Serializable
data class ALOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @EncodeDefault
    val expires: Long = System.currentTimeMillis() + TOKEN_LIFETIME,
) {
    fun isExpired() = System.currentTimeMillis() + EXPIRY_MARGIN > expires
}
