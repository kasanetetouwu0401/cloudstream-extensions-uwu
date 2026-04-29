package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://anime.oploverz.ac"
    private val backAPI = "https://backapi.oploverz.ac"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("Serial TV", true) -> TvType.Anime
                t.contains("OVA", true) -> TvType.OVA
                t.contains("Movie", true) || t.contains("BD", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
        
        var context: android.content.Context? = null

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Berlangsung", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "latest" to "Rilis Terbaru",
        "Sedang Trending" to "Sedang Trending",
        "Tayangan Baru Ditambahkan" to "Tayangan Baru Ditambahkan"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = ArrayList<SearchResponse>()
        var hasNextPage = false

        if (request.data == "latest") {
            val response = app.get("$backAPI/api/episodes?page=$page&pageSize=24&sort=${request.data}", referer = "$mainUrl/")
                .parsedSafe<Anime>()
            
            response?.data?.forEach { item ->
                val series = item.series ?: return@forEach
                home.add(
                    newAnimeSearchResponse(
                        series.title ?: "",
                        "$mainUrl/series/${series.slug}",
                        TvType.Anime
                    ) {
                        this.otherName = series.japaneseTitle
                        this.posterUrl = series.poster
                        this.score = Score.from10(series.score)
                        addSub(item.episodeNumber?.toIntOrNull() ?: series.totalEpisodes)
                    }
                )
            }
            hasNextPage = home.isNotEmpty()
            
        } else {
            if (page > 1) return newHomePageResponse(request.name, home, false)

            val document = app.get(mainUrl).document
            val sectionHeader = document.select("p.text-2xl:contains(${request.name})").first()
            val sectionContainer = sectionHeader?.parent()
            val addedUrls = mutableSetOf<String>()
            
            sectionContainer?.select("a[href^=/series/]")?.forEach { aTag ->
                val url = aTag.attr("href")
                if (!addedUrls.contains(url)) {
                    addedUrls.add(url)
                    val img = aTag.selectFirst("img")
                    val title = img?.attr("alt") ?: ""
                    val poster = img?.attr("src")

                    home.add(
                        newAnimeSearchResponse(title, fixUrl(url), TvType.Anime) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
            hasNextPage = false
        }

        if (home.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(request.name, home, hasNextPage)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$backAPI/api/series?q=$query", referer = "$mainUrl/")
            .parsedSafe<SearchAnime>()?.data?.map {
                newAnimeSearchResponse(
                    it.title ?: "",
                    "$mainUrl/series/${it.slug}",
                    TvType.Anime
                ) {
                    this.otherName = it.japaneseTitle
                    this.posterUrl = it.poster
                    this.score = Score.from10(it.score)
                    addSub(it.totalEpisodes)
                }
            }
    }

    private fun Document.selectList(selector: String): String {
        return this.select("ul.grid.list-inside li:contains($selector:)").text().substringAfter(":")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).body.string().let { Jsoup.parse(it) }

        val title = document.selectFirst("p.text-2xl.font-semibold")?.text() ?: ""
        val poster = document.selectFirst("img.h-full.w-full")?.attr("src")
        val tags = document.selectList("Genre").split(",").map { it.trim() }

        val year = document.selectList("Tanggal Rilis").let {
            Regex("\\d{4}").find(it)?.groupValues?.get(0)?.toIntOrNull()
        }
        val status = getStatus(document.selectList("Status").trim())
        val type = getType(document.selectList("Tipe"))
        val description = document.select("div.flex.w-full p").text().trim()

        val episodes =
            document.select("a.ring-offset-background.gap-2").mapIndexedNotNull { index, element ->
                val episode =
                    element.select("p:first-child").text().filter { it.isDigit() }.toIntOrNull()
                        ?: (index + 1)
                val link = fixUrl(element.attr("href"))
                newEpisode(url = link, initializer = { this.episode = episode }, fix = false)
            }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("div.flex.flex-row.items-start").amap { selector ->
            val qualityText = selector.select("div.w-20 > p").text().trim()
            val quality = getQuality(qualityText)

            selector.select("div.flex.flex-row.flex-wrap > a").amap { server ->
                val link = server.attr("href")
                if (link.isNotBlank()) {
                    loadFixedExtractor(link, quality, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getQuality(quality: String) : Int {
        return when {
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("480", true) || quality.equals("Mini", false) -> Qualities.P480.value
            quality.contains("720", true) || quality.equals("HD", false) -> Qualities.P720.value
            quality.contains("1080", true) || quality.equals("FHD", false) -> Qualities.P1080.value
            quality.contains("2160", true) || quality.contains("4k", true) -> Qualities.P2160.value
            else -> getQualityFromName(quality)
        }
    }

    data class Anime(
        @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
    )

    data class SearchAnime(
        @JsonProperty("data") val data: ArrayList<Series>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("episodeNumber") val episodeNumber: String? = null,
        @JsonProperty("series") val series: Series? = null,
    )

    data class Series(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japaneseTitle") val japaneseTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("score") val score: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    )
}
