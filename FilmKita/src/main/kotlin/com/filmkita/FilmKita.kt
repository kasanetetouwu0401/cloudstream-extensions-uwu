package com.filmkita

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class FilmKita : MainAPI() {

    override var mainUrl = "https://s1.iix.llc"
    override var name = "FilmKita🪅"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "year/2025/page/%d/" to "Terbaru",
        "category/tv-series/page/%d/" to "TV Series",
        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",
        "category/comedy/page/%d/" to "Comedy",
        "category/crime/page/%d/" to "Crime",
        "category/drama/page/%d/" to "Drama",
        "category/fantasy/page/%d/" to "Fantasy",
        "category/horror/page/%d/" to "Horror",
        "category/mystery/page/%d/" to "Mystery",
        "category/romance/page/%d/" to "Romance",
        "country/china/page/%d/" to "China",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/philippines/page/%d/" to "Philippines",
        "country/thailand/page/%d/" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
val document = app.get("$mainUrl/${request.data.format(page)}").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")!!.attr("href"))
        val ratingText = selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val posterUrl = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a")
            .text()
            .trim()
            .replace("-", "")

        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
            app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left img")?.getImageAttr())?.fixImageQuality()

        val tags = document.select("strong:contains(Genre) ~ a").eachText()
        val year = document.select("strong:contains(Year:) ~ a").text().toIntOrNull()
        val description = document.selectFirst("div[itemprop=description] p")?.text()?.trim()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()

        val actors = document.select("span[itemprop=actors] a").map { it.text() }
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")

        var seriesDoc = document

        if (url.contains("/eps/")) {
            val seriesLink = document.selectFirst("div.gmr-listseries a")?.attr("href")
            if (seriesLink != null && seriesLink.contains("/tv/")) {
                seriesDoc = app.get(seriesLink).document
            }
        }

        val episodeElements = seriesDoc.select("div.gmr-listseries a")

        if (episodeElements.isNotEmpty()) {

            val episodes = episodeElements
                .mapNotNull { ep ->

                    val epUrl = fixUrl(ep.attr("href"))
                    if (!epUrl.contains("/eps/")) return@mapNotNull null

                    val epTitle = ep.text().trim()

                    val season = Regex("""S(\d+)""")
                        .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val episode = Regex("""Eps?\s*(\d+)""")
                        .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    Pair(
                        episode,
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
                .sortedBy { it.first ?: 0 }
                .map { it.second }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                addTrailer(trailer, referer = mainUrl, addRaw = true)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addScore(rating)
            addActors(actors)
            addTrailer(trailer, referer = mainUrl, addRaw = true)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val servers = document.select("ul.muvipro-player-tabs li a")
            .mapNotNull { it.attr("href").takeIf { it.isNotBlank() } }
            .distinct()

        for (serverUrl in servers) {

            val urlToLoad =
                if (serverUrl.startsWith("/"))
                    "$mainUrl${serverUrl}"
                else serverUrl

            val serverDoc = app.get(urlToLoad).document

            serverDoc.select("iframe, video source").forEach { element ->

                val src =
                    element.attr("data-litespeed-src").takeIf { it.isNotBlank() }
                        ?: element.attr("src").takeIf { it.isNotBlank() }
                        ?: return@forEach

                val fixed = httpsify(src)

                if (!fixed.startsWith("http")) return@forEach

                loadExtractor(
                    fixed,
                    data,
                    subtitleCallback,
                    callback
                )
            }

            serverDoc.select("ul.gmr-download-list li a, a.download-link")
                .forEach { linkEl ->

                    val downloadUrl =
                        linkEl.attr("href").takeIf { it.isNotBlank() }
                            ?: return@forEach

                    val fixed = httpsify(downloadUrl)

                    if (!fixed.startsWith("http")) return@forEach

                    loadExtractor(
                        fixed,
                        data,
                        subtitleCallback,
                        callback
                    )
                }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return replace(regex, "")
    }

    private fun httpsify(url: String): String {
        return if (url.startsWith("http")) url else url.replaceFirst("http:", "https:")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
