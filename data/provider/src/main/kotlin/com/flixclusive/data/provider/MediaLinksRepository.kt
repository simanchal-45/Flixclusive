package com.flixclusive.data.provider

import com.flixclusive.core.util.R
import com.flixclusive.core.util.common.dispatcher.AppDispatchers
import com.flixclusive.core.util.common.dispatcher.Dispatcher
import com.flixclusive.core.util.common.resource.Resource
import com.flixclusive.core.util.common.ui.UiText
import com.flixclusive.core.util.exception.actualMessage
import com.flixclusive.core.util.exception.toNetworkException
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.model.provider.MediaLink
import com.flixclusive.model.provider.Stream
import com.flixclusive.model.provider.Subtitle
import com.flixclusive.model.tmdb.Film
import com.flixclusive.model.tmdb.FilmDetails
import com.flixclusive.model.tmdb.Movie
import com.flixclusive.model.tmdb.TvShow
import com.flixclusive.model.tmdb.common.tv.Episode
import com.flixclusive.provider.ProviderApi
import com.flixclusive.provider.ProviderWebViewApi
import com.flixclusive.provider.webview.ProviderWebView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaLinksRepository @Inject constructor(
    @Dispatcher(AppDispatchers.IO)
    private val ioDispatcher: CoroutineDispatcher,
    @Dispatcher(AppDispatchers.Main)
    private val mainDispatcher: CoroutineDispatcher,
) {

    /**
     *
     * Obtains the list of [MediaLink] of a given [FilmDetails] from the given [ProviderApi].
     *
     * @param api The api to be used to obtain the links.
     * @param watchId The unique identifier to be used to obtain the links.
     * @param film A detailed film object used to obtain the links. It could either be a [Movie] or a [TvShow]
     * @param episode An episode data used to obtain the links if the [film] parameter is a [TvShow]
     * @param onLinkFound A callback function that is invoked when a [Stream] or [Subtitle] is found.
     *
     * @return a [Resource] of [List] of [MediaLink]
     * */
    suspend fun getLinks(
        api: ProviderApi,
        watchId: String,
        film: FilmDetails,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ): Resource<Unit> {
        return withContext(ioDispatcher) {
            var webView: ProviderWebView? = null

            try {
                if (api is ProviderWebViewApi) {
                    withContext(mainDispatcher) {
                        webView = api.getWebView()
                    }

                    webView!!.getLinks(
                        watchId = watchId,
                        film = film,
                        episode = episode,
                        onLinkFound = onLinkFound
                    )
                } else {
                    api.getLinks(
                        watchId = watchId,
                        film = film,
                        episode = episode,
                        onLinkFound = onLinkFound
                    )
                }

                Resource.Success(Unit)
            } catch (e: Throwable) {
                e.toNetworkException()
            } finally {
                withContext(mainDispatcher) {
                    webView?.destroy()
                }
            }
        }
    }

    /**
     *
     * Method to be called if a [Film] directly
     * comes from TMDB Meta Provider. This method
     * obtains the watch id of the film based on the
     * given [ProviderApi].
     *
     * @param film The detailed film object of the film.
     * @param api The provider api to be used to obtain the watch id.
     * */
    suspend fun getWatchId(
        film: FilmDetails?,
        api: ProviderApi,
    ): Resource<String?> {
        return withContext(ioDispatcher) {
            try {
                if (film == null)
                    return@withContext Resource.Failure(R.string.default_error)

                var i = 1
                var watchId: String? = null
                val maxPage = 3

                while (watchId == null) {
                    if (i > maxPage) {
                        return@withContext Resource.Success(null)
                    }

                    val searchResponse = api.search(
                        title = film.title,
                        id = film.identifier,
                        imdbId = film.imdbId,
                        tmdbId = film.tmdbId,
                        page = i
                    )

                    if (searchResponse.results.isEmpty())
                        return@withContext Resource.Success(null)


                    for (item in searchResponse.results) {
                        val respectiveId = item.getRespectiveId(film)
                        if (respectiveId != null) {
                            watchId = respectiveId
                            break
                        }

                        val filmTypeMatches = item.filmType.type == film.filmType.type
                        val titleMatches = item.title.titleMatches(film.title)
                        val releaseDateMatches = item.dateMatches(film)
                        val yearReleaseMatches = item.yearMatches(film)

                        if (titleMatches && filmTypeMatches && film is TvShow) {
                            if (yearReleaseMatches || releaseDateMatches) {
                                watchId = item.identifier
                                break
                            }

                            val tvShowInfo = api.getFilmDetails(film = item)

                            if (
                                tvShowInfo.yearMatches(film)
                                || film.seasonCountMatches(tvShowInfo)
                            ) {
                                watchId = item.identifier
                                break
                            }
                        }

                        if (titleMatches && filmTypeMatches && (yearReleaseMatches || releaseDateMatches)) {
                            watchId = item.identifier
                            break
                        }
                    }

                    if (searchResponse.hasNextPage) {
                        i++
                    } else if (watchId.isNullOrEmpty()) {
                        break
                    }
                }

                Resource.Success(watchId)
            } catch (e: Throwable) {
                errorLog(e)
                Resource.Failure(
                    UiText.StringResource(
                        R.string.failed_to_fetch_media_id_message,
                        e.actualMessage
                    )
                )
            }
        }
    }

    private fun String.compareIgnoringSymbols(other: String?): Boolean {
        val lettersAndNumbersOnlyRegex = Regex("[^A-Za-z0-9 ]")

        val normalizedThis = this.replace(lettersAndNumbersOnlyRegex, "")
        val normalizedOther = other?.replace(lettersAndNumbersOnlyRegex, "")

        return normalizedThis.equals(normalizedOther, ignoreCase = true)
    }

    private fun Film.yearMatches(other: Film)
            = when {
        other.year == null || this.year == null -> false
        else -> this.year == other.year
    }

    private fun Film.dateMatches(other: Film)
            = when {
        other.releaseDate.isNullOrBlank() || this.releaseDate.isNullOrBlank() -> false
        else -> releaseDate.equals(other.releaseDate, true)
    }

    private fun String.titleMatches(other: String?)
            = when {
        other.isNullOrBlank() || isNullOrBlank() -> false
        else -> equals(other, true) || compareIgnoringSymbols(other)
    }

    private fun TvShow.seasonCountMatches(other: FilmDetails)
            = this.seasons.size == (other as TvShow).totalSeasons

    private fun Film.getRespectiveId(other: Film): String? {
        return when {
            id == other.id -> identifier
            tmdbId == other.tmdbId -> identifier
            imdbId == other.imdbId -> identifier
            identifier == other.identifier -> identifier
            else -> null
        }
    }
}