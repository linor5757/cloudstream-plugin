package com.linor

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.linor.shared.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

//https://cloudstream-plugin-vn-upbv.vercel.app/master.m3u8
val linkWork = "https://workers-playground-orange-scene-c305.chopnhoang999.workers.dev/files"
val linkMenu = "https://api.vieon.vn/backend/cm/v5/menu?platform=web&ui=012021"
val platform = "platform=web&ui=012021"
private var ribbonIdCache: String? = null
private var ribbonIdMapCache: Map<String, String>? = null
private val menuMutex = Mutex()
private val movieListSemaphore = Semaphore(permits = 10)


class PhimBetaProvider(val plugin: PhimBetaPlugin) : MainAPI() {
    override var lang = "vi"
    override var name = "Phim Việt"
    override var mainUrl = "https://api.vieon.vn/backend/cm/v5"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        Pair(mainUrl, "THỊNH HÀNH/vertical"),
        Pair(mainUrl, "PHIM CHIẾU RẠP VIỆT NAM/vertical"),
        Pair(mainUrl, "PHIM BỘ VIỆT NAM/vertical"),
        Pair(mainUrl, "KÊNH TRUYỀN HÌNH/horizontal"),
        Pair(mainUrl, "PHIM TVB MỚI NHẤT - CLICK NGAY/vertical"),
        Pair(mainUrl, "PHIM BỘ CHÂU Á LỒNG TIẾNG/vertical"),
        Pair(mainUrl, "PHIM BỘ HÀN QUỐC/vertical"),
        Pair(mainUrl, "PHIM BỘ TRUNG QUỐC/vertical"),
        Pair(mainUrl, "ANIME/vertical"),
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    var mainUrlImage = "https://phimimg.com"

    private suspend fun request(url: String): NiceResponse {
        return app.get(url)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "${mainUrl}/search?limit=60&keyword=${query}&page=0&tags=&${platform}"
        val itemsJson = tryParseJson<Json2Items>(app.get(url).text) ?: return null
        return this.getMoviesList(itemsJson)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val name = request.name.split("/")[0]
        val horizontal = request.name.split("/")[1] == "horizontal"

        menuMutex.withLock {
            if (ribbonIdMapCache == null) {
                // Chỉ request 1 lần duy nhất
                val jsonMenus = tryParseJson<List<Json2Menu>>(app.get(linkMenu).text)?.firstOrNull()
                val idMenu = jsonMenus?.subMenu?.map { it.id }?.firstOrNull() ?: return null
                ribbonIdCache = idMenu

                val ribbonLink = "$mainUrl/page_ribbons/$idMenu?$platform"
                val jsonMainList = tryParseJson<List<Json2MainPage>>(app.get(ribbonLink).text) ?: return null

                ribbonIdMapCache = jsonMainList.associate {
                    it.name.orEmpty() to it.id.orEmpty()
                }
            }
        }

        val ribbonId = ribbonIdMapCache?.get(name) ?: return null
        val ribbonUrl = "$mainUrl/ribbon/$ribbonId?limit=30&page=${page - 1}&$platform"
        val itemsJson = tryParseJson<Json2Items>(app.get(ribbonUrl).text) ?: return null
        val items = getMoviesList(itemsJson) ?: return null

        val homePageList = HomePageList(
            name = name,
            items,
            isHorizontalImages = horizontal
        )

        return newHomePageResponse(list = homePageList, hasNext = items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        if (url.contains("livetv")) {
            val jsonLive = withContext(Dispatchers.IO) {
                val contentText = request(url).text
                tryParseJson<Json2Play>(contentText)
            } ?: return@coroutineScope newMovieLoadResponse("", url, TvType.Others, null)
            return@coroutineScope newLiveStreamLoadResponse("",url,url)
            { this.posterUrl = jsonLive.images?.logo }
        }

        val jsonContent = withContext(Dispatchers.IO) {
            val contentText = request(url).text
            tryParseJson<Json2Content>(contentText)
        } ?: return@coroutineScope newMovieLoadResponse("", url, TvType.Others, null)

        val title = jsonContent.title ?: ""
        val backdrop = jsonContent.images?.posterV4 ?: jsonContent.images?.thumbnailV4
        val region = jsonContent.tags?.find { it.type == "country" }?.name
        val duration = jsonContent.runtime
        val categories = jsonContent.tags?.filter { it.type == "genre" }?.mapNotNull { it.name } ?: emptyList()
        val safeCategories = Utils.splitCategoriesWithPrefix(Utils.formatCategoriesWithEmoji(categories))
        val description = jsonContent.longDescription.orEmpty()
        val eps = jsonContent.episode ?: 0
        val totalEpisodes = jsonContent.currentEpisode?.toIntOrNull() ?: 0

        // 2. Tải song song: cast + recommend + episodes nếu có
        val recommendDeferred = async(Dispatchers.IO) {
            val recommendLink = "$mainUrl/related/${jsonContent.id}?limit=30&$platform"
            tryParseJson<Json2Items>(app.get(recommendLink).text)?.let { getMoviesList(it) }
        }

        val castDeferred = async {
            retryIO {
                jsonContent.people?.actor?.map {
                    Actor(it.name.orEmpty(), it.images?.avatar)
                }.orEmpty()
            }
        }

        val episodes = if (eps > 1) {
            val pageSize = 30
            val totalPages = (totalEpisodes + pageSize - 1) / pageSize
            (0 until totalPages).map { page ->
                async(Dispatchers.IO) {
                    val link = "$mainUrl/episode/${jsonContent.id}?limit=$pageSize&page=$page&$platform"
                    tryParseJson<Json2Items>(request(link).text)
                }
            }.awaitAll()
                .filterNotNull()
                .flatMap { res ->
                    res.items.map {
                        val link = "$mainUrl/content_detail/${it.groupId}?eps_id=${it.id}&$platform"
                        Episode(data = link, name = it.title)
                    }
                }
        } else emptyList()

        val castList = castDeferred.await()
        val recommendList = recommendDeferred.await()

        // 3. Trả về kết quả
        if (eps > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.plot = formatPlot(jsonContent.releaseYear, region, jsonContent.avgRate, duration, safeCategories, description)
                this.posterUrl = backdrop
                this.recommendations = recommendList
                addActors(castList)
            }
        } else {
            val movieLink = "$mainUrl/content_detail/${jsonContent.id}?eps_id=&$platform"
            newMovieLoadResponse(title, url, TvType.Movie, movieLink) {
                this.plot = formatPlot(jsonContent.releaseYear, region, jsonContent.avgRate, duration, safeCategories, description)
                this.posterUrl = backdrop
                this.recommendations = recommendList
                addActors(castList)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playLink = tryParseJson<Json2Play>(app.get(data).text)
        val subtitleUrl = playLink?.subtitles?.find { it.title?.lowercase() == "tiếng việt" }?.uri
        val h264 = playLink?.playLinks?.h264?.hls ?: return false
        val h265 = playLink.playLinks?.h265?.hls ?: ""
        val streamLinks = if (h265.isNotEmpty()) h265 else h264
        if (data.contains("livetv")) {
            val streamText = app.get(streamLinks).text
            val (audioUrl, videoUrl) = parseLiveM3u8(streamLinks, streamText)
            if (videoUrl == null) return false

            val combinedManifest = if (audioUrl != null) {
                generateCombinedM3U8(videoUrl, audioUrl)
            } else {
                // Nếu không có audio track riêng, phát videoUrl trực tiếp
                return generateM3u8(
                    "LiveTV",
                    streamLinks,
                    referer = mainUrl.getDomain()
                ).forEach(callback).let { true }
            }

            val requestBody = combinedManifest.trimIndent()

            val uniqueKey = "LiveTV|$audioUrl|$videoUrl"
            val safeName = name.replace("\\s+".toRegex(), "_")
            val filename = "track_${safeName}_${uniqueKey.hashCode()}.m3u8"
            var liveLink = uploadToGitHubM3u8(filename, content = requestBody)
            val res = app.head(liveLink)
            if (res.code in 400..500) {
                val response = app.post(
                    "$linkWork/$filename",
                    requestBody = requestBody.toRequestBody("text/plain".toMediaType())
                )
                liveLink = tryParseJson<Json2Link>(response.body.string())?.url.orEmpty()
            }
            callback.invoke(newExtractorLink(name, "LiveTV", liveLink, ExtractorLinkType.M3U8) {
                referer = mainUrl.getDomain()
            })
        }
        val resText = app.get(streamLinks).text
        val link4K = parseM3u8(resText, 3840, 2160)
        val link2K = parseM3u8(resText, 2560, 1440)
        val linkFullHD = parseM3u8(resText, 1920, 1080)
        val linkHD = parseM3u8(resText, 1280, 720)

        val links = listOf(
            "FullHD" to linkFullHD,
            "4K" to link4K,
            "2K" to link2K,
            "HD" to linkHD
        )

        for ((label, link) in links) {
            val video = link.videoHD ?: continue

            link.audioLt?.takeIf { it.isNotEmpty() }?.let {
                val url = uploadTrack("Lồng Tiếng", it, video = video)
                callback.invoke(newExtractorLink(name, "Lồng Tiếng-$label", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl.getDomain()
                })

                if (!subtitleUrl.isNullOrBlank()) {
                    subtitleCallback(SubtitleFile("Vietnamese", subtitleUrl))
                }
            }

            link.audioTm?.takeIf { it.isNotEmpty() }?.let {
                val url = uploadTrack("Thuyết Minh", it, video = video)
                callback.invoke(newExtractorLink(name, "Thuyết Minh-$label", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl.getDomain()
                })

                if (!subtitleUrl.isNullOrBlank()) {
                    subtitleCallback(SubtitleFile("Vietnamese", subtitleUrl))
                }
            }

            link.audioTg?.takeIf { it.isNotEmpty() }?.let {
                val url = uploadTrack("Tiếng Gốc", it, link.subtitleVi, video = video)
                callback.invoke(newExtractorLink(name, "VietNam-$label", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl.getDomain()
                })

                if (!subtitleUrl.isNullOrBlank() && link.subtitleVi.isNullOrBlank()) {
                    subtitleCallback(SubtitleFile("Vietnamese", subtitleUrl))
                }
            }
        }

        return true
    }

    suspend fun uploadTrack(name: String, audioUrl: String?, subtitleUrl: String? = null, video: String): String {
        val audioGroup = "audio/mp4a"
        val subsGroup = "subs"
        val mediaLines = buildList {
            if (!audioUrl.isNullOrEmpty()) {
                add("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="$audioGroup",LANGUAGE="vi",NAME="$name",DEFAULT=YES,AUTOSELECT=YES,URI="$audioUrl"""")
            }
            if (!subtitleUrl.isNullOrEmpty()) {
                add("""#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="$subsGroup",LANGUAGE="vi",NAME="VietSub",DEFAULT=YES,AUTOSELECT=YES,URI="$subtitleUrl"""")
            }
        }.joinToString("\n")

        val streamInf = """#EXT-X-STREAM-INF:BANDWIDTH=6734175,RESOLUTION=1920x1080,CODECS="avc1.4D4028,mp4a.40.2"${if (audioUrl != null) ",AUDIO=\"$audioGroup\"" else ""}${if (subtitleUrl != null) ",SUBTITLES=\"$subsGroup\"" else ""}"""

        val requestBody = """
            #EXTM3U
            #EXT-X-VERSION:6
            $mediaLines
            $streamInf
            $video
        """.trimIndent()

        val uniqueKey = "$name|$audioUrl|$video|$subtitleUrl"
        val safeName = name.replace("\\s+".toRegex(), "_")
        val filename = "track_${safeName}_${uniqueKey.hashCode()}.m3u8"
        val linkGithub = uploadToGitHubM3u8(filename, content = requestBody)
        println("❌ linkGithub : ${linkGithub}")
        val res = app.head(linkGithub)
        if (res.code in 400..500) {
            val response = app.post(
                "$linkWork/$filename",
                requestBody = requestBody.toRequestBody("text/plain".toMediaType())
            )
            return tryParseJson<Json2Link>(response.body.string())?.url.orEmpty()
        }
        return linkGithub
    }

    private fun getImageUrl(url: String): String {
        return if (!url.contains("http")) "${mainUrlImage}/${url.trimStart('/')}" else url
    }

    private suspend fun getMoviesList(itemsJson: Json2Items): List<SearchResponse>? = coroutineScope {
        val filterItem = itemsJson.items.filter {
            it.isPremium == 0 && it.title?.contains("Vie Channel") == false}

        filterItem.map { movie ->
            async {
                movieListSemaphore.withPermit {
                    try {
                        val live = movie.seo?.slug?.contains("truyen-hinh-truc-tuyen")
                        var poster = movie.images?.posterV4 ?: movie.images?.thumbnailV4 ?: return@withPermit null
                        var movieUrl = "${mainUrl}/content/${movie.id}?$platform"
                        val quality = if (movie.resolution == 4) "4K" else null
                        if (live == true) {
                            poster = movie.images?.thumbnailV4 ?: movie.images?.logo ?: return@withPermit null
                            movieUrl = "$mainUrl/livetv/detail/${movie.id}?$platform"
                        }

                        newAnimeSearchResponse("", movieUrl, TvType.TvSeries, true) {
                            posterUrl = poster
                            quality?.let { addQuality(it) }
                        }
                    } catch (e: Exception) {
                        println("❌ Lỗi xử lý movie ${movie.title}: ${e.message}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
}
//Thêm preload ảnh bằng coroutine nếu cần song song
//(Chỉ nên dùng nếu bạn cần kiểm tra ảnh từ trước, ví dụ test lỗi ảnh chết):
// imageDeferred = async(Dispatchers.IO) {
//    try {
//        val imageUrl = getImageUrl(movie.posterUrl)
//        // Optionally preload image to cache or validate response
//        app.get(imageUrl).ok // check if accessible
//        imageUrl
//    } catch (e: Exception) {
//        "https://yourcdn.com/default-poster.jpg"
//    }
// }
