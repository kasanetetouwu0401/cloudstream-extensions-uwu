package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking

open class Qiwi : ExtractorApi() {
    override val name = "Qiwi"
    override val mainUrl = "https://qiwi.gg"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val title = document.select("title").text()
        val source = document.select("video source").attr("src")

        if (!source.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source,
                    referer = "$mainUrl/",
                    quality = getIndexQuality(title),
                    type = INFER_TYPE,
                    headers = mapOf("Range" to "bytes=0-")
                )
            )
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

open class Filedon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url).document
        val token = res.select("meta[name=csrf-token]").attr("content")
        val slug = url.substringAfterLast("/")

        val video = app.post(
            "$mainUrl/get-url", data = mapOf(
                "_token" to token,
                "slug" to slug,
            ), referer = url
        ).parsedSafe<Response>()?.data?.url

        if (!video.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    video,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE
                )
            )
        }
    }

    data class Data(
        @JsonProperty("url") val url: String,
    )

    data class Response(
        @JsonProperty("data") val data: Data
    )
}

open class Buzzheavier : ExtractorApi() {
    override val name = "Buzzheavier"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val path = url.substringAfterLast("/")

        val video = app.get(fixUrl("/$path/download"), headers = mapOf(
            "HX-Current-URL" to url,
            "HX-Request" to "true"
        ), referer = url).headers["hx-redirect"]

        if (!video.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    video,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE
                )
            )
        }
    }
}

open class Akirabox : ExtractorApi() {
    override val name = "Akirabox"
    override val mainUrl = "https://akirabox.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val source = document.select("video source").attr("src")
        
        if (source.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE
                )
            )
        }
    }
}

open class Acefile : ExtractorApi() {
    override val name = "Acefile"
    override val mainUrl = "https://acefile.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val source = document.select("video source").attr("src")
        
        if (source.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "Google Drive (Acefile)",
                    source,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE
                )
            )
        }
    }
}
