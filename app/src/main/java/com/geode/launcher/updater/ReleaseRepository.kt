package com.geode.geodashlaunch.updater

import com.geode.geodashlaunch.utils.DownloadUtils.executeCoroutine
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.until
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL

class ReleaseRepository(private val httpClient: OkHttpClient) {
    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val GITHUB_API_HEADER = "X-GitHub-Api-Version"
        private const val GITHUB_API_VERSION = "2022-11-28"

        private const val GITHUB_RATELIMIT_REMAINING = "x-ratelimit-remaining"
        private const val GITHUB_RATELIMIT_RESET = "x-ratelimit-reset"
    }

    suspend fun getLatestLauncherRelease(): Release? {
        val releasePath = "$GITHUB_API_BASE/repos/ruingl/android-launcher/releases/latest"

        val url = URL(releasePath)

        return getReleaseByUrl(url)
    }

    suspend fun getLatestGeodeRelease(isNightly: Boolean = false): Release? {
        val geodeBaseUrl = "$GITHUB_API_BASE/repos/geode-sdk/geode/releases"
        val releasePath = if (isNightly) "$geodeBaseUrl/tags/nightly"
            else "$geodeBaseUrl/latest"

        val url = URL(releasePath)

        return getReleaseByUrl(url)
    }

    private fun generateRateLimitMessage(resetTime: Instant): String {
        val currentTime = Clock.System.now()
        val resetDelay = currentTime.until(resetTime, DateTimeUnit.SECOND)

        val formattedWait = "${resetDelay / 60}m"

        return "api ratelimit reached, try again in ${formattedWait}"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun getReleaseByUrl(url: URL): Release? {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader(GITHUB_API_HEADER, GITHUB_API_VERSION)
            .build()

        val call = httpClient.newCall(request)
        val response = call.executeCoroutine()

        return when (response.code) {
            200 -> {
                val format = Json {
                    namingStrategy = JsonNamingStrategy.SnakeCase
                    ignoreUnknownKeys = true
                }

                val release = format.decodeFromBufferedSource<Release>(
                    response.body!!.source()
                )

                release
            }
            403 -> {
                // determine if the error code is a ratelimit
                // (github docs say it sends 429 too, but haven't seen that)

                val limitRemaining = response.headers.get(GITHUB_RATELIMIT_REMAINING)?.toInt()
                val limitReset = response.headers.get(GITHUB_RATELIMIT_RESET)?.toLong()

                if (limitRemaining == 0 && limitReset != null) {
                    // handle ratelimit with a custom error
                    // there's also a retry-after header but again, haven't seen
                    val resetTime = Instant.fromEpochSeconds(limitReset, 0L)
                    val msg = generateRateLimitMessage(resetTime)

                    throw IOException(msg)
                }

                throw IOException("response 403: ${response.body!!.string()}")
            }
            404 -> {
                null
            }
            else -> {
                throw IOException("unknown response ${response.code}")
            }
        }
    }
}
