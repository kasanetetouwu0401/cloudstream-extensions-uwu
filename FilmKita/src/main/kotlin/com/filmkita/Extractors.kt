package com.filmkita

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HlsTerea : ExtractorApi() {

    override val name = "HlsTerea"
    override val mainUrl = "https://hls-terea.layarwibu.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val encoded = url.substringAfter("/player2/").substringBefore("?")
        val decoded = try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: Exception) {
            return
        }

        callback(
            newExtractorLink(
                name,
                name,
                decoded,
                ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
            }
        )
    }
}

class LayarWibu : ExtractorApi() {

    override val name = "LayarWibu"
    override val mainUrl = "https://hls-bekop.layarwibu.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val encoded = url.substringAfter("/player2/").substringBefore("?")
        val decoded = try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: Exception) {
            return
        }

        callback(
            newExtractorLink(
                name,
                name,
                decoded,
                ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
            }
        )
    }
}

class Minochinos : ExtractorApi() {

    override val name = "Minochinos"
    override val mainUrl = "https://minochinos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val fileCode = url.substringAfter("/embed/").substringBefore("/")
        if (fileCode.isBlank()) return

        val player = app.get(
            "$mainUrl/dl?op=view&file_code=$fileCode&embed=1",
            referer = url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        val stream = Regex("""/stream/[^"' ]+\.m3u8""")
            .find(player)
            ?.value ?: return

        val finalLink =
            if (stream.startsWith("http")) stream
            else "$mainUrl$stream"

        callback(
            newExtractorLink(
                name,
                name,
                finalLink,
                ExtractorLinkType.M3U8
            ) {
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
                quality = Qualities.Unknown.value
            }
        )
    }
}
