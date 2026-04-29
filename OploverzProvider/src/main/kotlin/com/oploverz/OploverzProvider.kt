package com.oploverz

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
        "latest" to "Latest Release",
        "Sedang Trending" to "Trending",
        "Tayangan Baru Ditambahkan" to "New Shows Added"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = ArrayList<SearchResponse>()
        var hasNextPage = false

        if (request.data == "latest") {
            val response = app.get("$backAPI/api/episodes?page=$page&pageSize=100&sort=${request.data}", referer = "$mainUrl/")
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

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {}
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover ?: poster

        val extractedEpisodes = document.select("a.ring-offset-background.gap-2").mapIndexedNotNull { index, element ->
            val episodeNum = element.select("p:first-child").text().filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
            val link = fixUrl(element.attr("href"))
            Pair(episodeNum, link)
        }.reversed()

        val isMovie = type == TvType.AnimeMovie || extractedEpisodes.isEmpty()

        val episodes = if (isMovie && extractedEpisodes.isEmpty()) {
            listOf(
                newEpisode(url) {
                    this.name = animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                    this.episode = 1
                    this.score = Score.from10(animeMetaData?.episodes?.get("1")?.rating)
                    this.posterUrl = animeMetaData?.episodes?.get("1")?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                    this.description = animeMetaData?.episodes?.get("1")?.overview ?: "No summary available"
                    this.addDate(animeMetaData?.episodes?.get("1")?.airDateUtc)
                    this.runTime = animeMetaData?.episodes?.get("1")?.runtime
                }
            )
        } else {
            extractedEpisodes.map { (episodeNum, link) ->
                val episodeKey = episodeNum.toString()
                val metaEp = animeMetaData?.episodes?.get(episodeKey)

                newEpisode(link) {
                    this.name = if (type == TvType.AnimeMovie) {
                        animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                    } else {
                        metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: "Episode $episodeNum"
                    }
                    this.episode = episodeNum
                    this.score = Score.from10(metaEp?.rating)
                    this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                    this.description = metaEp?.overview ?: "No summary available"
                    this.addDate(metaEp?.airDateUtc)
                    this.runTime = metaEp?.runtime
                }
            }
        }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val finalPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview ?: description

        val averageAniZipRating = animeMetaData?.episodes?.values
            ?.mapNotNull { it.rating?.toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.average()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.score = averageAniZipRating?.let { Score.from10(it) } ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.plot = finalPlot
            this.tags = tags
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch(_:Throwable){}
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    private fun parseAnimeData(jsonString: String): MetaAnimeData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, MetaAnimeData::class.java)
        } catch (_: Exception) {
            null
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

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie || type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
