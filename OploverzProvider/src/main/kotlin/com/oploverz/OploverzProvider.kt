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
        "Rilis Terbaru" to "Rilis Terbaru",
        "Sedang Trending" to "Sedang Trending",
        "Tayangan Baru Ditambahkan" to "Tayangan Baru Ditambahkan"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) throw ErrorLoadingException()

        val document = app.get(mainUrl).document
        val home = ArrayList<SearchResponse>()

        if (request.data == "Rilis Terbaru") {
            document.select("div.bg-card:has(a[href^=/series/])").forEach { element ->
                val aTag = element.selectFirst("a")
                val url = aTag?.attr("href") ?: return@forEach
                val seriesUrl = if (url.contains("/episode/")) url.substringBefore("/episode/") else url
                
                val img = aTag.selectFirst("img")
                val title = img?.attr("alt") ?: element.selectFirst("p.truncate")?.text() ?: ""
                val poster = img?.attr("src")
                val epText = element.select("p:contains(Episode)").text()
                val epNum = epText.filter { it.isDigit() }.toIntOrNull()

                home.add(
                    newAnimeSearchResponse(title, fixUrl(seriesUrl), TvType.Anime) {
                        this.posterUrl = poster
                        addSub(epNum)
                    }
                )
            }
        } else {
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
        }

        if (home.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(request.name, home)
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
        val document = app.get(data).document
        val html = document.html()

        val match = Regex("""data:\s*(\[.*?\]),\s*form:""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (match != null) {
            val jsonString = match.groupValues[1]
            val parsed = AppUtils.parseJson<List<SvelteData>>(jsonString)
            
            parsed.forEach { item ->
                val episode = item.data?.episode ?: return@forEach
                
                episode.streamUrl?.forEach { stream ->
                    val url = stream.url
                    if (!url.isNullOrEmpty()) {
                        val quality = getQuality(stream.source ?: "")
                        loadFixedExtractor(url, quality, data, subtitleCallback, callback)
                    }
                }
                
                episode.downloadUrl?.forEach { formatInfo ->
                    formatInfo.resolutions?.forEach { res ->
                        val quality = getQuality(res.quality ?: "")
                        res.download_links?.forEach { link ->
                            val url = link.url
                            if (!url.isNullOrEmpty()) {
                                loadFixedExtractor(url, quality, data, subtitleCallback, callback)
                            }
                        }
                    }
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
            else -> Qualities.Unknown.value
        }
    }

    data class SearchAnime(
        @JsonProperty("data") val data: ArrayList<Series>? = arrayListOf(),
    )

    data class Series(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("seriesId") val seriesId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japaneseTitle") val japaneseTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("score") val score: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    )

    data class SvelteData(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("data") val data: SvelteInnerData? = null
    )

    data class SvelteInnerData(
        @JsonProperty("episode") val episode: SvelteEpisode? = null
    )

    data class SvelteEpisode(
        @JsonProperty("streamUrl") val streamUrl: List<SvelteStream>? = null,
        @JsonProperty("downloadUrl") val downloadUrl: List<SvelteDownload>? = null
    )

    data class SvelteStream(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class SvelteDownload(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("resolutions") val resolutions: List<SvelteResolution>? = null
    )

    data class SvelteResolution(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("download_links") val download_links: List<SvelteDownloadLink>? = null
    )

    data class SvelteDownloadLink(
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
