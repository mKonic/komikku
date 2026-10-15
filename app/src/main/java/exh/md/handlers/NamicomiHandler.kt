package exh.md.handlers

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.Page
import exh.md.dto.NamicomiPageListDto
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class NamicomiHandler(currentClient: OkHttpClient, userAgent: String) {
    private val json by injectLazy<Json>()

    private val apiUrl = "https://api.namicomi.com"

    val headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .build()

    val client: OkHttpClient = currentClient

    suspend fun fetchPageList(externalUrl: String, dataSaver: Boolean): List<Page> {
        val chapterId = externalUrl.substringBefore("?").substringAfterLast("/")
        val request =
            GET(
                "$apiUrl/images/chapter/$chapterId?newQualities=true",
                headers,
            )
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            response.close()
            throw when (response.code) {
                402 -> Exception("This chapter requires a purchase on NamiComi")
                else -> HttpException(response.code)
            }
        }
        return pageListParse(response, chapterId, dataSaver)
    }

    private fun pageListParse(response: Response, chapterId: String, dataSaver: Boolean): List<Page> {
        val data = with(json) { response.parseAs<NamicomiPageListDto>() }.data
            ?: throw Exception("error getting images")

        /* Available quality levels: source, high, medium, low */
        val prefix = "${data.baseUrl}/chapter/$chapterId/${data.hash}/${if (dataSaver) "low" else "source"}/"

        return (if (dataSaver) data.low else data.source).mapIndexed { index, element ->
            val url = prefix + element.filename
            Page(index, url, url)
        }
    }
}
