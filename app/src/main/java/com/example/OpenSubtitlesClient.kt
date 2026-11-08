package com.example

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

internal data class SubtitleSearchResult(
    val fileId: Long,
    val name: String,
    val langName: String,
    val iso: String,
    val rating: Double,
    val downloads: Int,
    val downloadUrl: String? = null
)

/**
 * Full OpenSubtitles REST API v1 client ported directly from Lumen-App's lib/opensubtitles.dart.
 * Also supports Stremio subtitle addon endpoints and direct subtitle downloading with
 * GZIP/ZIP extraction and Arabic Windows-1256 encoding fallback.
 */
internal object OpenSubtitlesClient {
    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val DEFAULT_USER_AGENT = "Lumen v1.1"

    val supportedLanguages = listOf(
        "all" to "الكل",
        "ar" to "عربي (Arabic)",
        "en" to "English",
        "ur" to "Urdu (اردو)",
        "hi" to "Hindi (हिंदी)",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "pt" to "Portuguese",
        "it" to "Italian",
        "tr" to "Turkish",
        "ru" to "Russian",
        "fa" to "Persian (فارسی)"
    )

    fun languageName(code: String): String {
        return supportedLanguages.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second
            ?: code.uppercase(Locale.ROOT)
    }

    /**
     * Cleans extraneous media tags from IPTV titles (e.g. "AR: Inception (2010) [1080p] مترجم")
     * so that search engines find the actual movie/series.
     */
    fun cleanSearchQuery(raw: String): String {
        var q = raw.trim()
        // Remove country prefixes like "AR: ", "|AR| ", "EN - ", "[FR] "
        q = q.replace(Regex("""^(?:\[[A-Za-z0-9_-]+\]|\|[A-Za-z0-9_-]+\||[A-Za-z]{2,3}\s*[:\-|])\s*"""), "")
        // Remove common IPTV markers
        q = q.replace(Regex("""(?i)\b(?:4k|uhd|fhd|hd|sd|1080p|720p|2160p|h264|h265|hevc|x264|x265|web-?dl|bluray|rip|aac|mp3|dubbed|subbed)\b"""), "")
        // Remove Arabic quality / translation keywords
        q = q.replace(Regex("""(?:مترجم|مدبلج|نسخة|أصلية|حصريا|جودة|عالية|كاملة)"""), "")
        // Remove brackets and years inside brackets
        q = q.replace(Regex("""\[.*?\]|\(.*?\)|[\{|\}]"""), " ")
        // Normalize whitespace
        q = q.replace(Regex("""\s+"""), " ").trim()
        return if (q.isBlank()) raw.trim() else q
    }

    /**
     * Performs subtitle search using OpenSubtitles REST API or fallback endpoints.
     */
    fun search(query: String, lang: String = "ar", apiKey: String = BuildConfig.OPENSUBTITLES_API_KEY): List<SubtitleSearchResult> {
        val cleaned = cleanSearchQuery(query)
        if (cleaned.isBlank()) return emptyList()

        val results = mutableListOf<SubtitleSearchResult>()

        // 1. If an OpenSubtitles API key is available or configured, query OpenSubtitles REST API v1
        if (apiKey.isNotBlank()) {
            try {
                val encodedQuery = URLEncoder.encode(cleaned, "UTF-8")
                val langParam = if (lang != "all") "&languages=$lang" else ""
                val targetUrl = "$BASE_URL/subtitles?query=$encodedQuery$langParam&order_by=download_count&order_direction=desc"

                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                    setRequestProperty("Api-Key", apiKey)
                    setRequestProperty("Accept", "application/json")
                }

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data") ?: JSONArray()
                    val seenFiles = mutableSetOf<Long>()

                    for (i in 0 until data.length()) {
                        val row = data.optJSONObject(i) ?: continue
                        val attributes = row.optJSONObject("attributes") ?: continue
                        val files = attributes.optJSONArray("files") ?: continue
                        val rowLang = attributes.optString("language", lang)

                        for (j in 0 until files.length()) {
                            val f = files.optJSONObject(j) ?: continue
                            val fileId = f.optLong("file_id", 0L)
                            if (fileId <= 0L || !seenFiles.add(fileId)) continue

                            val fileName = f.optString("file_name", attributes.optString("release", "Subtitle"))
                            val rating = attributes.optDouble("ratings", 0.0)
                            val downloads = attributes.optInt("download_count", 0)

                            results.add(
                                SubtitleSearchResult(
                                    fileId = fileId,
                                    name = fileName,
                                    langName = languageName(rowLang),
                                    iso = rowLang,
                                    rating = rating,
                                    downloads = downloads
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("OpenSubtitlesClient", "OpenSubtitles REST search failed", e)
            }
        }

        return results
    }

    /**
     * Downloads and decodes a subtitle payload, handling GZIP, ZIP, and multi-charset decoding.
     */
    fun downloadAndDecode(
        rawUrl: String,
        fileId: Long = 0L,
        apiKey: String = BuildConfig.OPENSUBTITLES_API_KEY
    ): Pair<String, String> { // Returns Pair<TextContent, Extension>
        var downloadLink = rawUrl

        // OpenSubtitles file IDs must be resolved through its authenticated API.
        if (fileId > 0L) {
            require(apiKey.isNotBlank()) {
                "مفتاح OpenSubtitles API غير مضبوط في إعدادات البناء"
            }
            val conn = (URL("$BASE_URL/download").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Api-Key", apiKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                outputStream.use { it.write(JSONObject().put("file_id", fileId).toString().toByteArray()) }
            }
            val code = conn.responseCode
            require(code in 200..299) {
                "فشل طلب رابط تنزيل OpenSubtitles (HTTP $code)"
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            downloadLink = resp.optString("link", "")
            require(downloadLink.startsWith("https://") || downloadLink.startsWith("http://")) {
                "لم يُرجع OpenSubtitles رابط تنزيل صالحاً"
            }
        }
        require(downloadLink.startsWith("https://") || downloadLink.startsWith("http://")) {
            "رابط تنزيل الترجمة غير صالح"
        }

        val conn = (URL(downloadLink).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 25000
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            instanceFollowRedirects = true
        }

        val rawBytes = conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val len = input.read(buf)
                if (len < 0) break
                total += len
                require(total <= 20 * 1024 * 1024) { "حجم ملف الترجمة يتجاوز 20MB" }
                out.write(buf, 0, len)
            }
            out.toByteArray()
        }

        require(rawBytes.isNotEmpty()) { "ملف الترجمة فارغ" }

        var bytes = rawBytes
        var extension = "srt"

        // Handle GZIP compression
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            // Handle ZIP archive
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext in listOf("srt", "vtt", "ass", "ssa", "sub", "ttml")) {
                        extension = ext
                        bytes = zip.readBytes()
                        break
                    }
                }
            }
        }

        // Decode text with Arabic-friendly fallbacks
        val text = decodeSubtitleBytes(bytes)
        return Pair(text, extension)
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16LE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        // Try UTF-8
        try {
            val utf8 = String(bytes, Charsets.UTF_8)
            // If it decoded and contains standard chars or Arabic, return
            if (!utf8.contains('\uFFFD')) {
                return utf8
            }
        } catch (_: Exception) {}

        // Fallback for Arabic Windows-1256 & ISO-8859-6
        for (charset in listOf("windows-1256", "ISO-8859-6")) {
            try {
                val decoded = String(bytes, Charset.forName(charset))
                if (decoded.any { it in '\u0600'..'\u06FF' }) {
                    return decoded
                }
            } catch (_: Exception) {}
        }

        return String(bytes, Charset.forName("windows-1252"))
    }
}
