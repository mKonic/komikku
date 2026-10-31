package eu.kanade.tachiyomi.data.track.kavita

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: Int,
    val name: String,
    val thumbnail_url: String? = "",
    val pages: Int,
    val pagesRead: Int,
) {
    fun toTrack(): TrackSearch = TrackSearch.create(TrackerManager.KAVITA).also {
        it.title = name
        it.summary = ""
    }
}

@Serializable
data class VolumeDto(
    val id: Int,
    val number: Int,
    val name: String,
    val pages: Int,
    val pagesRead: Int,
    val lastModified: String,
    val created: String,
    val seriesId: Int,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class ChapterDto(
    val id: Int? = -1,
    val range: String? = "",
    val number: String? = "-1",
    val pages: Int? = 0,
    val isSpecial: Boolean? = false,
    val title: String? = "",
    val pagesRead: Int? = 0,
    val coverImageLocked: Boolean? = false,
    val volumeId: Int? = -1,
    val created: String? = "",
)

@Serializable
data class AuthenticationDto(
    val username: String,
    val token: String,
    val apiKey: String,
)

class OAuth(
    val authentications: List<SourceAuth> = listOf(
        SourceAuth(1),
        SourceAuth(2),
        SourceAuth(3),
    ),
) {
    fun getToken(apiUrl: String): String? {
        for (authentication in authentications) {
            if (authentication.apiUrl == apiUrl) {
                return authentication.jwtToken
            }
        }
        return null
    }
}

data class SourceAuth(
    var sourceId: Int,
    var apiUrl: String = "",
    var jwtToken: String = "",
)
