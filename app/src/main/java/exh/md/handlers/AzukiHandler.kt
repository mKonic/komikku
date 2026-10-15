package exh.md.handlers

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.Page
import exh.md.dto.AzukiPageListDto
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.injectLazy

class AzukiHandler(currentClient: OkHttpClient, userAgent: String) {
    private val json by injectLazy<Json>()
    val baseUrl = "https://www.omoi.com"
    private val apiUrl = "https://production.api.azuki.co"
    val headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("azuki-organization-key", "199e5a19-a236-49f5-81f4-43d4a541748a")
        .build()

    val client: OkHttpClient = currentClient.newBuilder()
        .addInterceptor(AzukiImageInterceptor)
        .build()

    /** The group was renamed to Omoi, while older chapter links still use Azuki. */
    fun isChapterUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return CHAPTER_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isImageUrl(url: String?): Boolean = url?.toHttpUrlOrNull()?.queryParameter("drm") == "1"

    suspend fun fetchPageList(externalUrl: String): List<Page> {
        val chapterId = externalUrl.substringAfterLast("/").substringBefore("?")
        val response = client.newCall(pageListRequest(chapterId)).await()
        if (!response.isSuccessful) {
            response.close()
            throw when (response.code) {
                401, 403 -> Exception("Log in via WebView and purchase this chapter to read.")
                404 -> Exception("This chapter is not available.")
                else -> HttpException(response.code)
            }
        }
        return pageListParse(response)
    }

    private fun pageListRequest(chapterId: String): Request {
        val token = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == "idToken" }?.value
        return GET(
            "$apiUrl/chapters/$chapterId/pages/v1",
            token?.let { headers.newBuilder().add("x-user-token", it).build() } ?: headers,
        )
    }

    fun pageListParse(response: Response): List<Page> {
        return with(json) { response.parseAs<AzukiPageListDto>() }.data.pages.mapIndexed { i, page ->
            val highRes = page.image.webp.maxByOrNull { it.width } ?: throw Exception("No image urls found for page $i")
            val imageUrl = highRes.url.toHttpUrl().newBuilder()
                .addQueryParameter("drm", "1")
                .build()
                .toString()
            Page(i, imageUrl = imageUrl)
        }
    }

    private companion object {
        val CHAPTER_HOSTS = listOf("omoi.com", "azuki.co")
    }
}

/** Omoi images with `drm=1` are obfuscated by XOR-ing each byte with 174. */
private object AzukiImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful || request.url.queryParameter("drm") != "1") return response

        val contentType = response.body.contentType()
        val bytes = response.body.bytes()
        if (ImageUtil.findImageType(bytes.inputStream()) == null) {
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor XOR_KEY).toByte()
        }
        return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
    }

    private const val XOR_KEY = 174
}
