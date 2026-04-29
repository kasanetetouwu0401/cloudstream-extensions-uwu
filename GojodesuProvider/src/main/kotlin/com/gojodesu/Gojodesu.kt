package com.gojodesu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Gojodesu : MainAPI() {
    override var mainUrl = "https://gojodesu.com"
    override var name = "Gojodesu"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        var context: android.content.Context? = null
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?sub=&order=latest" to "Baru Ditambahkan",
        "anime/?status=&type=&order=popular" to "Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        
        // Miku prioritaskan ambil dari eggtitle biar dapet judul bersih ♪
        val title = selectFirst(".eggtitle")?.text()?.trim()
            ?: selectFirst("div.tt h2")?.text()?.trim()
            ?: selectFirst("div.tt")?.text()?.trim()
            ?: selectFirst("a")?.attr("title")?.trim()
            ?: return null
            
        val cleanTitle = title
            .replace(Regex("\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b", RegexOption.IGNORE_CASE), "")
            // Jaga-jaga kalau fallback ke div.tt, kita sapu bersih tulisan episodenya ✧
            .replace(Regex("(?i)\\s*Episode\\s*\\d+.*"), "") 
            .replace(Regex("\\s+"), " ")
            .trim()
            
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(link), TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        
        val title = selectFirst(".eggtitle")?.text()?.trim()
            ?: selectFirst("div.tt h2")?.text()?.trim()
            ?: selectFirst("div.tt")?.text()?.trim()
            ?: selectFirst("a")?.attr("title")?.trim() 
            ?: return null
            
        val cleanTitle = title
            .replace(Regex("\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*Episode\\s*\\d+.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            
        val posterUrl = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        
        return newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.replace(Regex("\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia)\\b", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }

        val description = document.select("div.entry-content p")
            .joinToString("\n") { it.text() }
            .trim()

        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()

        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            h * 60 + m
        }

        val typeText = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()
        val type = getType(typeText)

        val tags = document.select("div.genxed a").map { it.text() }
        val actors = document.select("span:has(b:matchesOwn(Artis:)) a").map { it.text().trim() }
        val rating = document.selectFirst("div.rating strong")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()
            ?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val status = getStatus(document.selectFirst("span:matchesOwn(Status:)")?.ownText()?.trim())

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendResult() }

        val castList = document.select("div.bixbox.charvoice div.cvitem").mapNotNull { item ->
            val charBox = item.selectFirst(".cvsubitem.cvchar") ?: item
            val actorBox = item.selectFirst(".cvsubitem.cvactor") ?: item

            val charName = charBox.selectFirst(".cvcontent .charname")?.text()?.trim()
            val charRole = charBox.selectFirst(".cvcontent .charrole")?.text()?.trim()
            val charImg = charBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

            val actorName = actorBox.selectFirst(".cvcontent .charname")?.text()?.trim()
            val actorImg = actorBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

            if (charName.isNullOrBlank() && actorName.isNullOrBlank()) return@mapNotNull null

            val actor = Actor(charName ?: actorName ?: "", charImg)
            val voiceActor = actorName?.let { Actor(it, actorImg) }
            ActorData(
                actor = actor,
                roleString = charRole,
                voiceActor = voiceActor,
            )
        }

        val episodeElements = document.select("div.eplister ul li a")
        val episodes = episodeElements
            .reversed()
            .mapIndexed { index, aTag ->
                val href = fixUrl(aTag.attr("href"))
                newEpisode(href) {
                    name = "Episode ${index + 1}"
                    episode = index + 1
                }
            }

        val altTitles = listOfNotNull(
            title,
            document.selectFirst("span:matchesOwn(Judul Inggris:)")?.ownText()?.trim(),
            document.selectFirst("span:matchesOwn(Judul Jepang:)")?.ownText()?.trim(),
            document.selectFirst("span:matchesOwn(Judul Asli:)")?.ownText()?.trim(),
        ).distinct()

        val malIdFromPage = document.selectFirst("a[href*=\"myanimelist.net/anime/\"]")
            ?.attr("href")
            ?.substringAfter("/anime/", "")
            ?.substringBefore("/")
            ?.toIntOrNull()
        val aniIdFromPage = document.selectFirst("a[href*=\"anilist.co/anime/\"]")
            ?.attr("href")
            ?.substringAfter("/anime/", "")
            ?.substringBefore("/")
            ?.toIntOrNull()

        val tracker = APIHolder.getTracker(altTitles, TrackerType.getTypes(type), year, true)

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                this.plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addEpisodes(DubStatus.Subbed, episodes)
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Ambil iframe utama yang sudah ter-render di HTML
        document.select(".player-embed iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(httpsify(src), data, subtitleCallback, callback)
            }
        }

        // 2. Cek opsi server mirror dari dropdown
        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (opt in mirrorOptions) {
            val value = opt.attr("value").trim()
            if (value.isBlank()) continue

            if (value.startsWith("http")) {
                if (value.contains(mainUrl) && value != data) {
                    try {
                        val mirrorDoc = app.get(value).document
                        mirrorDoc.select(".player-embed iframe").forEach { iframe ->
                            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                            if (src.isNotBlank()) {
                                loadExtractor(httpsify(src), value, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (!value.contains(mainUrl)) {
                    loadExtractor(httpsify(value), data, subtitleCallback, callback)
                }
            } else {
                // Fallback Base64
                try {
                    val decodedHtml = base64Decode(value.replace("\\s".toRegex(), ""))
                    val iframeSrc = Jsoup.parse(decodedHtml).select("iframe").attr("src")
                    if (iframeSrc.isNotBlank()) {
                        loadExtractor(httpsify(iframeSrc), data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Ekstrak link download alternatif (jika ada)
        val downloadLinks = document.select("div.dlbox li span.e a[href]")
        for (a in downloadLinks) {
            val url = a.attr("href").trim()
            if (url.isNotBlank()) {
                loadExtractor(httpsify(url), data, subtitleCallback, callback)
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
}
