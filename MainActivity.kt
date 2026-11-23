package com.pricefinder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class Marketplace(
    val id: String,
    val label: String,
    val shortCode: String,
    val colorHex: Long,
    val domain: String
) {
    WILDBERRIES("wb", "Wildberries", "WB", 0xFFCB11AB, "wildberries.ru"),
    OZON("ozon", "Ozon", "OZ", 0xFF005BFF, "ozon.ru"),
    YANDEX_MARKET("ym", "Я.Маркет", "ЯМ", 0xFFD99100, "market.yandex.ru"),
    DNS("dns", "DNS", "DNS", 0xFFFC6D00, "dns-shop.ru"),
    CITILINK("citilink", "Ситилинк", "СТ", 0xFFE65100, "citilink.ru"),
    MVIDEO("mvideo", "М.Видео", "МВ", 0xFFE31235, "mvideo.ru"),
    AVITO("avito", "Авито", "АВ", 0xFF00AAFF, "avito.ru"),
    OTHER("other", "Другие", "РУ", 0xFF546E7A, "")
}

enum class SortMode(val label: String) {
    CHEAPEST("Сначала дешёвые"),
    EXPENSIVE("Сначала дорогие"),
    RELEVANCE("Точнее по хар-кам")
}

data class ProductOffer(
    val id: String,
    val title: String,
    val brand: String = "",
    val priceRub: Int?,
    val oldPriceRub: Int? = null,
    val marketplace: Marketplace,
    val shopName: String,
    val url: String,
    val imageUrl: String? = null,
    val rating: Double? = null,
    val reviewsCount: Int? = null,
    val snippet: String = "",
    val sourceEngine: String
)

data class SearchOutcome(
    val offers: List<ProductOffer>,
    val notices: List<String>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF6BA6FF),
                    secondary = Color(0xFF90CAF9),
                    surface = Color(0xFF141820),
                    background = Color(0xFF0D1117)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF1565C0),
                    secondary = Color(0xFF0288D1),
                    surface = Color(0xFFFFFFFF),
                    background = Color(0xFFF3F6FA)
                )
            }
            MaterialTheme(colorScheme = colorScheme) {
                PriceFinderApp()
            }
        }
    }
}

private object PriceSearchEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val RUB_PRICE_REGEX = Regex(
        """(?<!\d)(\d{1,3}(?:[\s\u00A0\u202F]\d{3})+|\d{3,7})(?:[.,]\d{1,2})?\s*(?:₽|руб(?:лей|\.|)|р\.(?!\w))""",
        RegexOption.IGNORE_CASE
    )

    private val RUB_PREFIX_REGEX = Regex(
        """(?:₽|руб\.?)\s*(\d{1,3}(?:[\s\u00A0\u202F]\d{3})+|\d{3,7})(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    private val FOREIGN_CURRENCY_REGEX = Regex("""(\$|€|£|USD|EUR|GBP)""", RegexOption.IGNORE_CASE)

    suspend fun searchAll(
        productName: String,
        specs: String,
        selectedMarkets: Set<Marketplace>,
        enableDirectWb: Boolean,
        enableTavily: Boolean,
        enableDuckDuckGo: Boolean,
        tavilyKey: String,
        serpKiteKey: String,
        serperKey: String
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val fullQuery = listOf(productName.trim(), specs.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val notices = Collections.synchronizedList(mutableListOf<String>())
        val collected = mutableListOf<ProductOffer>()

        val activeMarkets = if (selectedMarkets.isEmpty()) Marketplace.entries.toSet() else selectedMarkets

        coroutineScope {
            val jobs = mutableListOf<Deferred<List<ProductOffer>>>()

            if (enableDirectWb && Marketplace.WILDBERRIES in activeMarkets) {
                jobs.add(async {
                    try {
                        searchWildberries(fullQuery)
                    } catch (e: Exception) {
                        notices.add("WB напрямую: ${e.message?.take(60) ?: "недоступен"}")
                        emptyList<ProductOffer>()
                    }
                })
            }

            if (enableTavily) {
                jobs.add(async {
                    try {
                        searchTavily(fullQuery, activeMarkets, tavilyKey.trim())
                    } catch (e: Exception) {
                        notices.add("Tavily: ${e.message?.take(85) ?: "ошибка"}")
                        emptyList<ProductOffer>()
                    }
                })
            }

            if (enableDuckDuckGo) {
                jobs.add(async {
                    try {
                        searchDuckDuckGo(fullQuery, activeMarkets)
                    } catch (e: Exception) {
                        notices.add("DuckDuckGo: ${e.message?.take(75) ?: "ошибка"}")
                        emptyList<ProductOffer>()
                    }
                })
            }

            if (serpKiteKey.isNotBlank()) {
                jobs.add(async {
                    try {
                        searchSerpKiteYandex(fullQuery, activeMarkets, serpKiteKey.trim())
                    } catch (e: Exception) {
                        notices.add("SerpKite Яндекс: ${e.message?.take(80) ?: "ошибка"}")
                        emptyList<ProductOffer>()
                    }
                })
            }

            if (serperKey.isNotBlank()) {
                jobs.add(async {
                    try {
                        searchSerperMarketplaces(fullQuery, activeMarkets, serperKey.trim())
                    } catch (e: Exception) {
                        notices.add("Serper: ${e.message?.take(80) ?: "ошибка"}")
                        emptyList<ProductOffer>()
                    }
                })
            }

            for (job in jobs) {
                collected.addAll(job.await())
            }
        }

        // Дедупликация по ссылке: приоритет у карточек с ценой и фото
        val deduped = LinkedHashMap<String, ProductOffer>()
        for (offer in collected) {
            val normUrl = offer.url.substringBefore("?").trimEnd('/').lowercase()
            val existing = deduped[normUrl]
            if (existing == null) {
                deduped[normUrl] = offer
            } else {
                val replaceWithPrice = existing.priceRub == null && offer.priceRub != null
                val replaceWithImage = existing.priceRub == offer.priceRub &&
                    existing.imageUrl.isNullOrBlank() && !offer.imageUrl.isNullOrBlank()
                if (replaceWithPrice || replaceWithImage) {
                    deduped[normUrl] = offer
                }
            }
        }

        SearchOutcome(
            offers = deduped.values.toList(),
            notices = notices.toList()
        )
    }

    private fun searchWildberries(query: String): List<ProductOffer> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "https://search.wb.ru/exactmatch/ru/common/v4/search?appType=1&curr=rub&dest=-1257786&lang=ru&page=1&query=$encoded&resultset=catalog&sort=popular&spp=30",
            "https://u-search.wb.ru/exactmatch/ru/common/v18/search?appType=1&curr=rub&dest=-1257786&lang=ru&page=1&query=$encoded&resultset=catalog&sort=popular&spp=30"
        )

        var lastError: Exception? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ru-RU,ru;q=0.9")
                    .header("Origin", "https://www.wildberries.ru")
                    .header("Referer", "https://www.wildberries.ru/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = RuntimeException("HTTP ${response.code} (если с VPN — попробуйте без него)")
                        return@use
                    }
                    val bodyStr = response.body?.string().orEmpty()
                    if (!bodyStr.trimStart().startsWith("{")) {
                        lastError = RuntimeException("защита WB")
                        return@use
                    }
                    val parsed = parseWildberriesJson(bodyStr)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError!!
        return emptyList()
    }

    private fun parseWildberriesJson(jsonStr: String): List<ProductOffer> {
        val root = JSONObject(jsonStr)
        val products = root.optJSONArray("products")
            ?: root.optJSONObject("data")?.optJSONArray("products")
            ?: return emptyList()

        val result = mutableListOf<ProductOffer>()
        val limit = minOf(products.length(), 45)
        for (i in 0 until limit) {
            val item = products.optJSONObject(i) ?: continue
            val nmId = item.optLong("id", 0L)
            if (nmId <= 0L) continue

            val brand = item.optString("brand", "").trim()
            val name = item.optString("name", "").trim()
            if (name.isBlank()) continue

            var productPriceRub: Int? = null
            var basicPriceRub: Int? = null

            val sizes = item.optJSONArray("sizes")
            if (sizes != null) {
                for (sIdx in 0 until sizes.length()) {
                    val sizeObj = sizes.optJSONObject(sIdx) ?: continue
                    val priceObj = sizeObj.optJSONObject("price") ?: continue
                    val p = priceObj.optLong("product", 0L)
                    if (p > 0L) {
                        productPriceRub = (p / 100L).toInt()
                        val b = priceObj.optLong("basic", 0L)
                        if (b > p) basicPriceRub = (b / 100L).toInt()
                        break
                    }
                }
            }
            if (productPriceRub == null) {
                val saleU = item.optLong("salePriceU", 0L)
                if (saleU > 0L) productPriceRub = (saleU / 100L).toInt()
                val priceU = item.optLong("priceU", 0L)
                if (priceU > saleU && saleU > 0L) basicPriceRub = (priceU / 100L).toInt()
            }

            if (productPriceRub == null || productPriceRub <= 0) continue

            val rawRating = item.optDouble("reviewRating", Double.NaN)
            val fallbackRating = item.optDouble("rating", Double.NaN)
            val rating = when {
                !rawRating.isNaN() && rawRating > 0.0 -> rawRating
                !fallbackRating.isNaN() && fallbackRating > 0.0 -> fallbackRating
                else -> null
            }
            val feedbacks = item.optInt("feedbacks", 0).takeIf { it > 0 }
            val fullTitle = if (brand.isNotBlank() && !name.startsWith(brand, ignoreCase = true)) {
                "$brand / $name"
            } else {
                name
            }

            result.add(
                ProductOffer(
                    id = "wb_$nmId",
                    title = fullTitle,
                    brand = brand,
                    priceRub = productPriceRub,
                    oldPriceRub = basicPriceRub,
                    marketplace = Marketplace.WILDBERRIES,
                    shopName = item.optString("supplier", "Wildberries").ifBlank { "Wildberries" },
                    url = "https://www.wildberries.ru/catalog/$nmId/detail.aspx",
                    imageUrl = wbImageUrl(nmId),
                    rating = rating,
                    reviewsCount = feedbacks,
                    sourceEngine = "WB напрямую"
                )
            )
        }
        return result
    }

    // Tavily Search: работает и в бесплатном режиме без ключа (X-Tavily-Access-Mode: keyless), и с ключом tvly-...
    private fun searchTavily(
        query: String,
        selectedMarkets: Set<Marketplace>,
        apiKey: String
    ): List<ProductOffer> {
        val defaultDomains = listOf(
            "ozon.ru",
            "market.yandex.ru",
            "dns-shop.ru",
            "citilink.ru",
            "mvideo.ru",
            "avito.ru",
            "wildberries.ru"
        )
        val domains = if (Marketplace.OTHER in selectedMarkets) {
            emptyList()
        } else {
            selectedMarkets.mapNotNull { it.domain.takeIf { d -> d.isNotBlank() } }.ifEmpty { defaultDomains }
        }

        val payload = JSONObject().apply {
            put("query", "$query купить цена рублей")
            put("search_depth", "basic")
            put("max_results", if (apiKey.isNotBlank()) 15 else 10)
            put("include_answer", false)
            put("include_images", true)
            if (domains.isNotEmpty()) {
                val arr = JSONArray()
                domains.forEach { arr.put(it) }
                put("include_domains", arr)
            }
        }

        val reqBuilder = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        } else {
            reqBuilder.header("X-Tavily-Access-Mode", "keyless")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw RuntimeException(
                    if (apiKey.isBlank()) "лимит режима без ключа. Вставь бесплатный ключ Tavily или SerpKite в ⚙️"
                    else "превышен лимит запросов"
                )
            }
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}")
            }

            val root = JSONObject(bodyStr)
            val results = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<ProductOffer>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val url = item.optString("url", "").trim()
                if (title.isBlank() || url.isBlank()) continue

                val content = item.optString("content", "").trim()
                val priceRub = extractRubPriceFromText("$title $content")
                val marketplace = detectMarketplace(url, title)

                // Извлекаем картинку из result.images (может быть строкой или объектом {url})
                var imageUrl: String? = null
                val itemImages = item.optJSONArray("images")
                if (itemImages != null && itemImages.length() > 0) {
                    val first = itemImages.opt(0)
                    imageUrl = when (first) {
                        is JSONObject -> first.optString("url", "").ifBlank { null }
                        is String -> first.ifBlank { null }
                        else -> null
                    }
                }

                list.add(
                    ProductOffer(
                        id = "tavily_$i",
                        title = cleanTitle(title),
                        priceRub = priceRub,
                        marketplace = marketplace,
                        shopName = extractHostName(url),
                        url = url,
                        imageUrl = imageUrl,
                        snippet = content,
                        sourceEngine = if (apiKey.isBlank()) "Tavily (без ключа)" else "Tavily"
                    )
                )
            }
            return list
        }
    }

    // DuckDuckGo HTML: прямой поиск без регистрации и без API-ключей
    private fun searchDuckDuckGo(
        query: String,
        selectedMarkets: Set<Marketplace>
    ): List<ProductOffer> {
        val domains = selectedMarkets.mapNotNull { it.domain.takeIf { d -> d.isNotBlank() } }
        val finalQuery = if (domains.isNotEmpty() && Marketplace.OTHER !in selectedMarkets) {
            val siteClause = domains.take(5).joinToString(" OR ") { "site:$it" }
            "$query купить цена ($siteClause)"
        } else {
            "$query купить цена ozon wildberries яндекс маркет dns ситилинк"
        }

        val formBody = FormBody.Builder()
            .add("q", finalQuery)
            .add("kl", "ru-ru")
            .build()

        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .header("Referer", "https://html.duckduckgo.com/")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 202 || response.code == 429) {
                throw RuntimeException("временная пауза DDG, повтори чуть позже")
            }
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}")
            }

            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val linkRegex = Regex(
                """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippetRegex = Regex(
                """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|span)>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            val matches = linkRegex.findAll(html).toList()
            val list = mutableListOf<ProductOffer>()

            for (idx in matches.indices) {
                val m = matches[idx]
                val rawHref = m.groupValues[1]
                val rawTitle = m.groupValues[2]

                val realUrl = resolveDdgUrl(rawHref) ?: continue
                if ("duckduckgo.com/" in realUrl) continue

                val sliceStart = m.range.last + 1
                val sliceEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else minOf(html.length, sliceStart + 2500)
                val chunk = if (sliceEnd > sliceStart) html.substring(sliceStart, sliceEnd) else ""
                val rawSnippet = snippetRegex.find(chunk)?.groupValues?.get(1).orEmpty()

                val title = decodeHtml(rawTitle).trim()
                val snippet = decodeHtml(rawSnippet).trim()
                if (title.isBlank()) continue

                val priceRub = extractRubPriceFromText("$title $snippet")
                val marketplace = detectMarketplace(realUrl, title)

                list.add(
                    ProductOffer(
                        id = "ddg_$idx",
                        title = cleanTitle(title),
                        priceRub = priceRub,
                        marketplace = marketplace,
                        shopName = extractHostName(realUrl),
                        url = realUrl,
                        snippet = snippet,
                        sourceEngine = "DuckDuckGo"
                    )
                )
            }
            return list
        }
    }

    private fun resolveDdgUrl(rawHref: String): String? {
        val unescaped = rawHref.replace("&amp;", "&").trim()
        val fullHref = when {
            unescaped.startsWith("//") -> "https:$unescaped"
            unescaped.startsWith("/") -> "https://html.duckduckgo.com$unescaped"
            else -> unescaped
        }
        return try {
            val uri = Uri.parse(fullHref)
            val uddg = uri.getQueryParameter("uddg")
            val target = if (!uddg.isNullOrBlank()) uddg else fullHref
            if (target.startsWith("http://") || target.startsWith("https://")) target else null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeHtml(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&#8381;", "₽")
            .replace("&#x20bd;", "₽", ignoreCase = true)
            .replace(Regex("""\s+"""), " ")
    }

    // SerpKite API: 2500 бесплатных поисков через GitHub/Google + 1000/мес, умеет искать через Яндекс
    private fun searchSerpKiteYandex(
        query: String,
        selectedMarkets: Set<Marketplace>,
        apiKey: String
    ): List<ProductOffer> {
        val domains = selectedMarkets.mapNotNull { it.domain.takeIf { d -> d.isNotBlank() } }
        val finalQuery = if (domains.isNotEmpty() && domains.size <= 3 && Marketplace.OTHER !in selectedMarkets) {
            val siteClause = domains.joinToString(" | ") { "site:$it" }
            "$query купить цена ($siteClause)"
        } else {
            "$query купить цена ozon wildberries яндекс маркет dns ситилинк"
        }

        val payload = JSONObject().apply {
            put("q", finalQuery)
            put("country", "ru")
            put("language", "ru")
            put("engine", "yandex")
            put("num", 20)
        }

        val request = Request.Builder()
            .url("https://api.serpkite.com/v1/search")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} (проверь ключ SerpKite)")
            }
            val root = JSONObject(bodyStr)
            val results = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<ProductOffer>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val link = item.optString("link", "").trim()
                if (title.isBlank() || link.isBlank()) continue

                val snippet = item.optString("snippet", "").trim()
                val priceRub = extractRubPriceFromText("$title $snippet")
                val marketplace = detectMarketplace(link, title)

                list.add(
                    ProductOffer(
                        id = "serpkite_$i",
                        title = cleanTitle(title),
                        priceRub = priceRub,
                        marketplace = marketplace,
                        shopName = extractHostName(link),
                        url = link,
                        snippet = snippet,
                        sourceEngine = "SerpKite Яндекс"
                    )
                )
            }
            return list
        }
    }

    private fun searchSerperMarketplaces(
        query: String,
        selectedMarkets: Set<Marketplace>,
        apiKey: String
    ): List<ProductOffer> {
        val domains = selectedMarkets.mapNotNull { it.domain.takeIf { d -> d.isNotBlank() } }
        val finalQuery = if (domains.isNotEmpty() && Marketplace.OTHER !in selectedMarkets) {
            val siteClause = domains.joinToString(" OR ") { "site:$it" }
            "$query купить цена ($siteClause)"
        } else {
            "$query купить цена (site:ozon.ru OR site:market.yandex.ru OR site:dns-shop.ru OR site:citilink.ru OR site:mvideo.ru OR site:avito.ru OR site:wildberries.ru)"
        }

        val payload = JSONObject().apply {
            put("q", finalQuery)
            put("gl", "ru")
            put("hl", "ru")
            put("num", 25)
        }
        val request = Request.Builder()
            .url("https://google.serper.dev/search")
            .header("X-API-KEY", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} (проверь ключ Serper)")
            }
            val root = JSONObject(bodyStr)
            val organic = root.optJSONArray("organic") ?: return emptyList()
            val list = mutableListOf<ProductOffer>()
            for (i in 0 until organic.length()) {
                val item = organic.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val link = item.optString("link", "").trim()
                if (title.isBlank() || link.isBlank()) continue

                val snippet = item.optString("snippet", "").trim()
                val currency = item.optString("currency", "")
                val structuredPrice = item.optDouble("price", Double.NaN)
                val priceRange = item.optString("priceRange", "")

                val priceRub = when {
                    !structuredPrice.isNaN() && structuredPrice >= 50.0 &&
                        !FOREIGN_CURRENCY_REGEX.containsMatchIn(currency) -> structuredPrice.toInt()
                    priceRange.isNotBlank() -> extractRubPriceFromText(priceRange)
                        ?: extractRubPriceFromText("$title $snippet")
                    else -> extractRubPriceFromText("$title $snippet")
                }

                val marketplace = detectMarketplace(link, title)
                val rawRating = item.optDouble("rating", Double.NaN)
                val rating = if (!rawRating.isNaN() && rawRating > 0.0) rawRating else null
                val ratingCount = item.optInt("ratingCount", 0).takeIf { it > 0 }

                list.add(
                    ProductOffer(
                        id = "serper_org_$i",
                        title = cleanTitle(title),
                        priceRub = priceRub,
                        marketplace = marketplace,
                        shopName = extractHostName(link),
                        url = link,
                        imageUrl = item.optString("imageUrl", "").ifBlank { null },
                        rating = rating,
                        reviewsCount = ratingCount,
                        snippet = snippet,
                        sourceEngine = "Serper"
                    )
                )
            }
            return list
        }
    }

    fun extractRubPriceFromText(rawText: String): Int? {
        val text = rawText
            .replace("*", " ")
            .replace("_", " ")
            .replace("`", " ")
            .replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace(Regex("""\s+"""), " ")

        val candidates = mutableListOf<Int>()

        for (match in RUB_PRICE_REGEX.findAll(text)) {
            val afterEnd = minOf(text.length, match.range.last + 20)
            val after = text.substring(match.range.last + 1, afterEnd).lowercase()
            // Игнорируем рассрочку/кредит "в мес" и "в нед"
            if (after.contains("мес") || after.contains("нед")) continue

            val digits = match.groupValues[1].filter { it.isDigit() }
            val value = digits.toIntOrNull()
            if (value != null && value in 50..50_000_000) {
                candidates.add(value)
            }
        }

        if (candidates.isEmpty()) {
            for (match in RUB_PREFIX_REGEX.findAll(text)) {
                val digits = match.groupValues[1].filter { it.isDigit() }
                val value = digits.toIntOrNull()
                if (value != null && value in 50..50_000_000) {
                    candidates.add(value)
                }
            }
        }

        return candidates.minOrNull()
    }

    private fun detectMarketplace(url: String, fallbackSource: String): Marketplace {
        val combined = "${url.lowercase()} ${fallbackSource.lowercase()}"
        return when {
            "wildberries" in combined || "wb.ru" in combined -> Marketplace.WILDBERRIES
            "ozon.ru" in combined || "озон" in combined -> Marketplace.OZON
            "market.yandex" in combined || "яндекс маркет" in combined || "яндекс.маркет" in combined -> Marketplace.YANDEX_MARKET
            "dns-shop" in combined || "днс" in combined -> Marketplace.DNS
            "citilink" in combined || "ситилинк" in combined -> Marketplace.CITILINK
            "mvideo" in combined || "м.видео" in combined -> Marketplace.MVIDEO
            "avito.ru" in combined || "авито" in combined -> Marketplace.AVITO
            else -> Marketplace.OTHER
        }
    }

    private fun extractHostName(url: String): String {
        return try {
            Uri.parse(url).host?.removePrefix("www.") ?: "Магазин"
        } catch (_: Exception) {
            "Магазин"
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*[-–|—]\s*(купить на OZON|купить в интернет-магазине|OZON|Wildberries|Яндекс Маркет|DNS).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { raw }
    }

    private fun wbImageUrl(nmId: Long): String {
        val vol = nmId / 100000L
        val part = nmId / 1000L
        val host = when {
            vol <= 143 -> "01"
            vol <= 287 -> "02"
            vol <= 431 -> "03"
            vol <= 719 -> "04"
            vol <= 1007 -> "05"
            vol <= 1061 -> "06"
            vol <= 1115 -> "07"
            vol <= 1169 -> "08"
            vol <= 1313 -> "09"
            vol <= 1601 -> "10"
            vol <= 1655 -> "11"
            vol <= 1919 -> "12"
            vol <= 2045 -> "13"
            vol <= 2189 -> "14"
            vol <= 2405 -> "15"
            vol <= 2621 -> "16"
            vol <= 2837 -> "17"
            vol <= 3053 -> "18"
            vol <= 3269 -> "19"
            vol <= 3485 -> "20"
            vol <= 3701 -> "21"
            vol <= 3917 -> "22"
            vol <= 4133 -> "23"
            vol <= 4349 -> "24"
            vol <= 4565 -> "25"
            vol <= 4877 -> "26"
            vol <= 5189 -> "27"
            vol <= 5501 -> "28"
            vol <= 5813 -> "29"
            vol <= 6125 -> "30"
            vol <= 6437 -> "31"
            vol <= 6749 -> "32"
            vol <= 7061 -> "33"
            vol <= 7373 -> "34"
            vol <= 7685 -> "35"
            vol <= 7997 -> "36"
            vol <= 8309 -> "37"
            vol <= 8621 -> "38"
            vol <= 8933 -> "39"
            vol <= 9245 -> "40"
            vol <= 9557 -> "41"
            else -> "42"
        }
        return "https://basket-$host.wbbasket.ru/vol$vol/part$part/$nmId/images/c246x328/1.webp"
    }
}

private fun formatRub(price: Int): String {
    val s = price.toString()
    val sb = StringBuilder()
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(s[i])
    }
    return "$sb ₽"
}

private fun parseSpecTokens(specs: String): List<String> {
    return specs.split(Regex("""[,;|\s]+"""))
        .map { it.trim().lowercase() }
        .filter { it.length >= 2 }
        .distinct()
}

private fun countSpecMatches(offer: ProductOffer, tokens: List<String>): Int {
    if (tokens.isEmpty()) return 0
    val haystack = "${offer.title} ${offer.brand} ${offer.snippet}".lowercase()
    return tokens.count { token -> haystack.contains(token) }
}

private fun buildDirectMarketplaceUrl(market: Marketplace, fullQuery: String): String {
    val q = URLEncoder.encode(fullQuery, "UTF-8")
    return when (market) {
        Marketplace.OZON -> "https://www.ozon.ru/search/?text=$q&sorting=price"
        Marketplace.WILDBERRIES -> "https://www.wildberries.ru/catalog/0/search.aspx?sort=priceup&search=$q"
        Marketplace.YANDEX_MARKET -> "https://market.yandex.ru/search?text=$q&how=aprice"
        Marketplace.DNS -> "https://www.dns-shop.ru/search/?q=$q&order=1"
        Marketplace.CITILINK -> "https://www.citilink.ru/search/?text=$q&sorting=price_asc"
        Marketplace.MVIDEO -> "https://www.mvideo.ru/product-list-page?q=$q&sort=price_asc"
        Marketplace.AVITO -> "https://www.avito.ru/rossiya?q=$q&s=1"
        Marketplace.OTHER -> "https://ya.ru/products/search?text=$q"
    }
}

private fun openExternalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceFinderApp() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("price_finder_prefs", Context.MODE_PRIVATE) }

    var tavilyKey by remember { mutableStateOf(prefs.getString("tavily_key", "") ?: "") }
    var serpKiteKey by remember { mutableStateOf(prefs.getString("serpkite_key", "") ?: "") }
    var serperKey by remember { mutableStateOf(prefs.getString("serper_key", "") ?: "") }

    var enableDirectWb by remember { mutableStateOf(prefs.getBoolean("enable_wb", true)) }
    var enableTavily by remember { mutableStateOf(prefs.getBoolean("enable_tavily", true)) }
    var enableDuckDuckGo by remember { mutableStateOf(prefs.getBoolean("enable_ddg", true)) }

    var history by remember {
        val saved = prefs.getString("history_json", "[]") ?: "[]"
        val arr = try { JSONArray(saved) } catch (_: Exception) { JSONArray() }
        val items = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) items.add(s)
        }
        mutableStateOf(items.toList())
    }

    var productName by remember { mutableStateOf("") }
    var specsText by remember { mutableStateOf("") }
    var lastSearchedQuery by remember { mutableStateOf("") }
    var lastSearchedSpecs by remember { mutableStateOf("") }

    var selectedMarkets by remember { mutableStateOf(setOf<Marketplace>()) }
    var minPriceText by remember { mutableStateOf("") }
    var maxPriceText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.CHEAPEST) }
    var onlyWithPrice by remember { mutableStateOf(false) }
    var strictSpecs by remember { mutableStateOf(false) }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var rawOffers by remember { mutableStateOf<List<ProductOffer>>(emptyList()) }
    var notices by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }

    fun saveHistory(queryEntry: String) {
        val updated = (listOf(queryEntry) + history.filterNot { it.equals(queryEntry, ignoreCase = true) }).take(6)
        history = updated
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        prefs.edit().putString("history_json", arr.toString()).apply()
    }

    fun runSearch() {
        val trimmedName = productName.trim()
        val trimmedSpecs = specsText.trim()
        if (trimmedName.isBlank() && trimmedSpecs.isBlank()) return

        keyboard?.hide()
        val combined = listOf(trimmedName, trimmedSpecs).filter { it.isNotBlank() }.joinToString(" ")
        lastSearchedQuery = combined
        lastSearchedSpecs = trimmedSpecs
        saveHistory(combined)

        scope.launch {
            isLoading = true
            notices = emptyList()
            hasSearched = true
            val outcome = PriceSearchEngine.searchAll(
                productName = trimmedName,
                specs = trimmedSpecs,
                selectedMarkets = selectedMarkets,
                enableDirectWb = enableDirectWb,
                enableTavily = enableTavily,
                enableDuckDuckGo = enableDuckDuckGo,
                tavilyKey = tavilyKey,
                serpKiteKey = serpKiteKey,
                serperKey = serperKey
            )
            rawOffers = outcome.offers
            notices = outcome.notices
            isLoading = false
        }
    }

    val specTokens = remember(lastSearchedSpecs) { parseSpecTokens(lastSearchedSpecs) }
    val minPrice = minPriceText.filter { it.isDigit() }.toIntOrNull()
    val maxPrice = maxPriceText.filter { it.isDigit() }.toIntOrNull()

    val filteredOffers = remember(
        rawOffers, selectedMarkets, minPrice, maxPrice, onlyWithPrice, strictSpecs, sortMode, specTokens
    ) {
        val list = rawOffers.filter { offer ->
            val marketOk = selectedMarkets.isEmpty() || offer.marketplace in selectedMarkets
            val pricePresentOk = !onlyWithPrice || offer.priceRub != null
            val minOk = minPrice == null || (offer.priceRub != null && offer.priceRub >= minPrice)
            val maxOk = maxPrice == null || (offer.priceRub != null && offer.priceRub <= maxPrice)
            val specsOk = !strictSpecs || specTokens.isEmpty() ||
                countSpecMatches(offer, specTokens) == specTokens.size
            marketOk && pricePresentOk && minOk && maxOk && specsOk
        }

        when (sortMode) {
            SortMode.CHEAPEST -> list.sortedWith(
                compareBy<ProductOffer> { it.priceRub == null }
                    .thenBy { it.priceRub ?: Int.MAX_VALUE }
                    .thenByDescending { countSpecMatches(it, specTokens) }
            )
            SortMode.EXPENSIVE -> list.sortedWith(
                compareBy<ProductOffer> { it.priceRub == null }
                    .thenByDescending { it.priceRub ?: -1 }
            )
            SortMode.RELEVANCE -> list.sortedWith(
                compareByDescending<ProductOffer> { countSpecMatches(it, specTokens) }
                    .thenBy { it.priceRub == null }
                    .thenBy { it.priceRub ?: Int.MAX_VALUE }
            )
        }
    }

    val activeEnginesText = buildList {
        if (enableDirectWb) add("WB")
        if (enableTavily) add(if (tavilyKey.isNotBlank()) "Tavily" else "Tavily(Free)")
        if (enableDuckDuckGo) add("DDG")
        if (serpKiteKey.isNotBlank()) add("SerpKite")
        if (serperKey.isNotBlank()) add("Serper")
    }.joinToString(" + ").ifBlank { "Выкл" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ЦенаПоиска", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Источники: $activeEnginesText",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = productName,
                            onValueChange = { productName = it },
                            label = { Text("Название товара") },
                            placeholder = { Text("Например: Ноутбук ASUS TUF Gaming") },
                            singleLine = true,
                            trailingIcon = {
                                if (productName.isNotEmpty()) {
                                    IconButton(onClick = { productName = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = specsText,
                            onValueChange = { specsText = it },
                            label = { Text("Характеристики (необязательно)") },
                            placeholder = { Text("RTX 4060 16GB 512GB 15.6") },
                            singleLine = true,
                            trailingIcon = {
                                if (specsText.isNotEmpty()) {
                                    IconButton(onClick = { specsText = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { runSearch() },
                                enabled = !isLoading && (productName.isNotBlank() || specsText.isNotBlank()),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Ищем лучшие цены...")
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Найти дешевле", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            FilledTonalIconButton(
                                onClick = { showAdvancedFilters = !showAdvancedFilters },
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Фильтры")
                            }
                        }

                        if (history.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(history) { oldQuery ->
                                    AssistChip(
                                        onClick = {
                                            productName = oldQuery
                                            specsText = ""
                                            runSearch()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.History,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                oldQuery,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 12.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (tavilyKey.isBlank() && serpKiteKey.isBlank() && serperKey.isBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSettingsDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Готово к работе без ключей!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Поиск по WB, Ozon, Я.Маркет, DNS и Авито уже включён. Для Яндекса без SMS можно позже добавить ключ SerpKite (в ⚙️).",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            item {
                val countsByMarket = remember(rawOffers) {
                    rawOffers.groupingBy { it.marketplace }.eachCount()
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedMarkets.isEmpty(),
                            onClick = { selectedMarkets = emptySet() },
                            label = {
                                val countSuffix = if (rawOffers.isNotEmpty()) " (${rawOffers.size})" else ""
                                Text("Все$countSuffix")
                            },
                            leadingIcon = if (selectedMarkets.isEmpty()) {
                                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                    items(Marketplace.entries) { market ->
                        val isSelected = market in selectedMarkets
                        val count = countsByMarket[market] ?: 0
                        val suffix = if (count > 0) " ($count)" else ""
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedMarkets = if (isSelected) {
                                    selectedMarkets - market
                                } else {
                                    selectedMarkets + market
                                }
                            },
                            label = { Text("${market.label}$suffix") },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            if (showAdvancedFilters) {
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Фильтры и сортировка", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = minPriceText,
                                    onValueChange = { minPriceText = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Цена от (₽)") },
                                    placeholder = { Text("20000") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = maxPriceText,
                                    onValueChange = { maxPriceText = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Цена до (₽)") },
                                    placeholder = { Text("120000") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(SortMode.entries) { mode ->
                                    FilterChip(
                                        selected = sortMode == mode,
                                        onClick = { sortMode = mode },
                                        label = { Text(mode.label, fontSize = 12.sp) }
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onlyWithPrice = !onlyWithPrice }
                            ) {
                                Checkbox(checked = onlyWithPrice, onCheckedChange = { onlyWithPrice = it })
                                Text("Показывать только товары с ценой", fontSize = 13.sp)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { strictSpecs = !strictSpecs }
                            ) {
                                Checkbox(checked = strictSpecs, onCheckedChange = { strictSpecs = it })
                                Text("Строгое совпадение всех характеристик", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            val activeQueryForLinks = lastSearchedQuery.ifBlank {
                listOf(productName.trim(), specsText.trim()).filter { it.isNotBlank() }.joinToString(" ")
            }
            if (activeQueryForLinks.isNotBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Открыть сразу по цене (дешевле →):",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val quickMarkets = listOf(
                                Marketplace.OZON,
                                Marketplace.WILDBERRIES,
                                Marketplace.YANDEX_MARKET,
                                Marketplace.DNS,
                                Marketplace.CITILINK,
                                Marketplace.AVITO
                            )
                            items(quickMarkets) { m ->
                                SuggestionChip(
                                    onClick = {
                                        openExternalUrl(context, buildDirectMarketplaceUrl(m, activeQueryForLinks))
                                    },
                                    label = { Text("${m.label} ↗", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }

            if (notices.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            notices.forEach { msg ->
                                Text(
                                    "• $msg",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            if (hasSearched && !isLoading) {
                item {
                    val cheapestPrice = filteredOffers.mapNotNull { it.priceRub }.minOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (cheapestPrice != null) {
                                "Найдено: ${filteredOffers.size} · от ${formatRub(cheapestPrice)}"
                            } else {
                                "Найдено: ${filteredOffers.size}"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = sortMode.label,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val all = SortMode.entries
                                sortMode = all[(all.indexOf(sortMode) + 1) % all.size]
                            }
                        )
                    }
                }
            }

            if (hasSearched && !isLoading && filteredOffers.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ShoppingCart,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Ничего не найдено по текущим фильтрам", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Попробуй убрать часть характеристик, ослабить фильтр цены или выбрать «Все» маркетплейсы.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredOffers,
                    key = { index, offer -> "${offer.id}_$index" }
                ) { _, offer ->
                    ProductOfferCard(
                        offer = offer,
                        specTokens = specTokens,
                        onOpen = { openExternalUrl(context, offer.url) }
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            tavilyKey = tavilyKey,
            serpKiteKey = serpKiteKey,
            serperKey = serperKey,
            enableDirectWb = enableDirectWb,
            enableTavily = enableTavily,
            enableDuckDuckGo = enableDuckDuckGo,
            onDismiss = { showSettingsDialog = false },
            onSave = { newTavily, newSerpKite, newSerper, newWb, newTavOn, newDdgOn ->
                tavilyKey = newTavily.trim()
                serpKiteKey = newSerpKite.trim()
                serperKey = newSerper.trim()
                enableDirectWb = newWb
                enableTavily = newTavOn
                enableDuckDuckGo = newDdgOn
                prefs.edit()
                    .putString("tavily_key", tavilyKey)
                    .putString("serpkite_key", serpKiteKey)
                    .putString("serper_key", serperKey)
                    .putBoolean("enable_wb", enableDirectWb)
                    .putBoolean("enable_tavily", enableTavily)
                    .putBoolean("enable_ddg", enableDuckDuckGo)
                    .apply()
                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun ProductOfferCard(
    offer: ProductOffer,
    specTokens: List<String>,
    onOpen: () -> Unit
) {
    val marketColor = Color(offer.marketplace.colorHex)
    val matchedSpecs = remember(offer, specTokens) { countSpecMatches(offer, specTokens) }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(marketColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = offer.marketplace.shortCode,
                    color = marketColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                )
                if (!offer.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = offer.imageUrl,
                        contentDescription = offer.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(marketColor.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (offer.marketplace == Marketplace.OTHER) offer.shopName else offer.marketplace.label,
                            color = marketColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = offer.sourceEngine,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )

                    if (offer.rating != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFA000),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = String.format("%.1f", offer.rating) +
                                    (offer.reviewsCount?.let { " ($it)" } ?: ""),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = offer.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )

                if (specTokens.isNotEmpty()) {
                    val allMatched = matchedSpecs == specTokens.size
                    val matchColor = if (allMatched) Color(0xFF2E7D32) else Color(0xFFEF6C00)
                    Text(
                        text = "Совпало характеристик: $matchedSpecs из ${specTokens.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = matchColor
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        if (offer.priceRub != null) {
                            Text(
                                text = formatRub(offer.priceRub),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (offer.oldPriceRub != null && offer.oldPriceRub > offer.priceRub) {
                                Text(
                                    text = formatRub(offer.oldPriceRub),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }
                        } else {
                            Text(
                                text = "Цена на сайте",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onOpen,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Открыть →", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    tavilyKey: String,
    serpKiteKey: String,
    serperKey: String,
    enableDirectWb: Boolean,
    enableTavily: Boolean,
    enableDuckDuckGo: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    var tKey by remember { mutableStateOf(tavilyKey) }
    var skKey by remember { mutableStateOf(serpKiteKey) }
    var srKey by remember { mutableStateOf(serperKey) }
    var wbOn by remember { mutableStateOf(enableDirectWb) }
    var tavOn by remember { mutableStateOf(enableTavily) }
    var ddgOn by remember { mutableStateOf(enableDuckDuckGo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки источников", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Встроенные (работают без ключей):", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Switch(checked = wbOn, onCheckedChange = { wbOn = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Wildberries напрямую", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Точные цены и фото", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Switch(checked = tavOn, onCheckedChange = { tavOn = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Tavily (Ozon, ЯМ, DNS, Авито)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Работает сразу без ключа (Keyless)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Switch(checked = ddgOn, onCheckedChange = { ddgOn = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("DuckDuckGo по магазинам РФ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Запасной поиск без ключей", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider()

                Text("1. SerpKite — Яндекс поиск (без SMS!)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Вход через GitHub/Google · 2500 поисков сразу + 1000/мес", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = skKey,
                    onValueChange = { skKey = it },
                    label = { Text("SerpKite API Key (необяз.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { openExternalUrl(context, "https://serpkite.com/") }) {
                    Text("Получить ключ serpkite.com ↗", fontSize = 12.sp)
                }

                HorizontalDivider()

                Text("2. Tavily API Key (если упёрся в лимит Keyless)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Вход через Google без карты и телефона · 1000/мес", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = tKey,
                    onValueChange = { tKey = it },
                    label = { Text("Tavily Key tvly-... (необяз.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { openExternalUrl(context, "https://app.tavily.com/") }) {
                    Text("Получить ключ app.tavily.com ↗", fontSize = 12.sp)
                }

                HorizontalDivider()

                Text("3. Serper Key (если есть)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = srKey,
                    onValueChange = { srKey = it },
                    label = { Text("Serper Key (необяз.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(tKey, skKey, srKey, wbOn, tavOn, ddgOn) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
