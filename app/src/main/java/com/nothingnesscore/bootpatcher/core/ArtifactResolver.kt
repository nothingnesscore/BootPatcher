package com.nothingnesscore.bootpatcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the newest successful BruhKernel artifact that matches a stock boot
 * image.
 *
 * Matching is strict by design. A kernel built for a different Android
 * version or security patch level will not boot, so an artifact that does not
 * match on every axis is treated as "not found" rather than "close enough".
 */
class ArtifactResolver(
    private val defaultRepo: String = "nothingnesscore/BruhKernel",
) {

    data class Artifact(
        val name: String,
        val downloadUrl: String,
        val createdAt: String,
        val runId: Long,
    )

    data class MatchResult(
        val artifact: Artifact?,
        /** Why nothing matched, in language the UI can show verbatim. */
        val reason: String,
        val candidatesSeen: Int,
    )

    private class NoMatchException(val reason: String) : Exception(reason)

    /**
     * @param repo owner/repo to search.
     * @param spec values taken from the user's stock boot image.
     */
    suspend fun findLatest(
        repo: String = defaultRepo,
        spec: TargetSpec,
        variant: String? = null,
        token: String? = null,
    ): MatchResult = withContext(Dispatchers.IO) {
        val artifacts = runCatching { fetchArtifacts(repo, token) }
            .getOrElse { return@withContext MatchResult(null, "Could not reach GitHub: ${it.message}", 0) }

        if (artifacts.isEmpty()) {
            return@withContext MatchResult(
                null,
                "No build artifacts are available in $repo yet.",
                0,
            )
        }

        val wanted = StringBuilder()
        // Android major, e.g. "android14" / "a14".
        wanted.append("(android|a)${spec.androidMajor}\\b")
        // Kernel version, bounded so 6.1.13 does not match 6.1.138.
        val kv = Regex.escape(spec.kernelVersion)
        wanted.append("(^|[^0-9.])${kv}([^0-9.]|$)")
        if (!spec.osPatchLevel.isBlank()) {
            wanted.append(Regex.escape(CompatibilityChecker.normalizeSpl(spec.osPatchLevel)))
        }
        val pattern = Regex(wanted.toString(), RegexOption.IGNORE_CASE)

        val matches = artifacts.filter { a ->
            val nameOk = pattern.containsMatchIn(a.name)
            val variantOk = variant == null || a.name.contains(variant, ignoreCase = true)
            nameOk && variantOk
        }

        if (matches.isEmpty()) {
            val want = buildString {
                append("Android ${spec.androidMajor} / kernel ${spec.kernelVersion}")
                if (spec.osPatchLevel.isNotBlank()) append(" / SPL ${spec.osPatchLevel}")
                if (!variant.isNullOrBlank()) append(" / $variant")
            }
            return@withContext MatchResult(
                null,
                "No build matches your boot image ($want).\n\n" +
                    "BruhKernel only publishes kernels for exact Android and security " +
                    "patch level combinations, because a mismatch cannot boot.\n\n" +
                    "To patch this image:\n" +
                    "1. Fork nothingnesscore/BruhKernel on GitHub\n" +
                    "2. Run the build workflow for your exact target\n" +
                    "3. Set the kernel repository to your fork in Settings",
                artifacts.size,
            )
        }

        val best = matches.maxBy { it.createdAt }
        MatchResult(best, "Matched ${best.name}", artifacts.size)
    }

    private fun fetchArtifacts(repo: String, token: String?): List<Artifact> {
        val out = mutableListOf<Artifact>()
        var page = 1
        // Three pages covers the retention window without hammering the API.
        while (page <= 3) {
            val url = "https://api.github.com/repos/$repo/actions/artifacts?per_page=100&page=$page"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "BootPatcher-Android")
                token?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val code = conn.responseCode
                if (code == 404) throw NoMatchException("Repository $repo was not found.")
                if (code == 401 || code == 403) {
                    throw NoMatchException(
                        "GitHub refused the request ($code). If $repo is private, add a token in Settings."
                    )
                }
                if (code != 200) throw NoMatchException("GitHub returned HTTP $code.")

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val arr = json.optJSONArray("artifacts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("expired")) continue
                    val run = o.optJSONObject("workflow_run")
                    out += Artifact(
                        name = o.optString("name"),
                        downloadUrl = o.optString("archive_download_url"),
                        createdAt = o.optString("created_at"),
                        runId = run?.optLong("id") ?: 0L,
                    )
                }
                // Stop when a page returns fewer than a full page of results.
                if (arr.length() < 100) break
                page++
            } finally {
                conn.disconnect()
            }
        }
        return out
    }
}