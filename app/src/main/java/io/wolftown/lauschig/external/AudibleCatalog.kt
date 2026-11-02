package io.wolftown.lauschig.external

import io.wolftown.lauschig.absapi.AbsJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Unofficial, account-less Audible catalog (research doc section 1). It may disappear; callers treat every failure as "no data". */
interface AudibleCatalog {
    /** Product with its series (`series`, `relationships`) or, for a series ASIN, its children (`relationships`). */
    @GET("1.0/catalog/products/{asin}")
    suspend fun product(
        @Path("asin") asin: String,
        @Query("response_groups") responseGroups: String = "series,relationships",
    ): AudibleProductResponse

    /** Children of a series: `relationships` entries with `relationship_to_product == "child"`. */
    @GET("1.0/catalog/products/{asin}")
    suspend fun seriesChildren(
        @Path("asin") seriesAsin: String,
        @Query("response_groups") responseGroups: String = "relationships",
    ): AudibleProductResponse

    /** Details for at most [MAX_ASINS_PER_CALL] ASINs; use [AudibleCatalog.productsChunked] for more. */
    @GET("1.0/catalog/products")
    suspend fun products(
        @Query("asins") asins: String,
        @Query("response_groups") responseGroups: String = DETAIL_GROUPS,
    ): AudibleProductsResponse

    companion object {
        const val MAX_ASINS_PER_CALL = 50
        const val DETAIL_GROUPS = "product_desc,contributors,media,product_attrs,series"
    }
}

/** Looks up details for any number of ASINs, 50 per request, and merges the answers. */
suspend fun AudibleCatalog.productsChunked(asins: List<String>): List<AudibleProduct> =
    asins.distinct().chunked(AudibleCatalog.MAX_ASINS_PER_CALL).flatMap { products(it.joinToString(",")).products }

@Serializable
data class AudibleProductResponse(val product: AudibleProduct? = null)

@Serializable
data class AudibleProductsResponse(val products: List<AudibleProduct> = emptyList())

@Serializable
data class AudibleProduct(
    val asin: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<AudiblePerson>? = null,
    val narrators: List<AudiblePerson>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("runtime_length_min") val runtimeLengthMin: Int? = null,
    val language: String? = null,
    @SerialName("format_type") val formatType: String? = null,
    @SerialName("product_images") val productImages: Map<String, String>? = null,
    @SerialName("merchandising_summary") val merchandisingSummary: String? = null,
    val series: List<AudibleSeriesRef>? = null,
    val relationships: List<AudibleRelationship>? = null,
)

@Serializable
data class AudiblePerson(val name: String? = null)

@Serializable
data class AudibleSeriesRef(val asin: String? = null, val title: String? = null, val sequence: String? = null)

@Serializable
data class AudibleRelationship(
    val asin: String? = null,
    @SerialName("relationship_to_product") val relationshipToProduct: String? = null,
    @SerialName("relationship_type") val relationshipType: String? = null,
    val sequence: String? = null,
    val sort: String? = null,
)

/** Base URL per Audible region (`de`, `us`, `uk`, ...); tests point every region at a MockWebServer. */
fun interface AudibleEndpoints {
    fun baseUrl(region: String): HttpUrl
}

object AudibleRegions {
    const val DEFAULT = "de"
    private val hosts = mapOf(
        "de" to "https://api.audible.de/", "us" to "https://api.audible.com/", "uk" to "https://api.audible.co.uk/",
        "fr" to "https://api.audible.fr/", "it" to "https://api.audible.it/", "es" to "https://api.audible.es/",
        "ca" to "https://api.audible.ca/", "au" to "https://api.audible.com.au/",
    )
    val all: Set<String> get() = hosts.keys

    val production = AudibleEndpoints { region -> (hosts[region] ?: hosts.getValue("us")).toHttpUrl() }

    /** The catalog the ASIN may live in instead: de <-> us, anything else -> us. */
    fun alternative(region: String): String = if (region == "us") "de" else "us"
}

object AudibleClient {
    /** No auth header, 10 s timeouts, JSON only. */
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Accept", "application/json").build())
        }
        .build()
}

/** One Retrofit [AudibleCatalog] per region, created lazily. */
@Singleton
class AudibleApis @Inject constructor(private val endpoints: AudibleEndpoints, @AudibleHttp private val client: OkHttpClient) {
    private val cache = HashMap<String, AudibleCatalog>()

    @Synchronized
    fun forRegion(region: String): AudibleCatalog = cache.getOrPut(region) {
        val base = endpoints.baseUrl(region).let { if (it.encodedPath.endsWith("/")) it else it.newBuilder().addPathSegment("").build() }
        Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(AbsJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()
            .create(AudibleCatalog::class.java)
    }
}
