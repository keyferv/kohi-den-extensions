package eu.kanade.tachiyomi.animeextension.es.doramasyt

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

object DoramasytFilters {

    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") {
        fun getQuery() = filter.changePrefix()
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.asQueryPart<CategoriesFilter>("categoria") +
                filters.asQueryPart<GenresFilter>("genero") +
                filters.asQueryPart<YearsFilter>("fecha") +
                filters.asQueryPart<LettersFilter>("letra"),
        )
    }

    // ── Filter UI classes (accept dynamic options) ──────────────────────

    class CategoriesFilter(options: Array<Pair<String, String>> = FALLBACK_CATEGORIES) :
        QueryPartFilter("Categoría", options)

    class GenresFilter(options: Array<Pair<String, String>> = FALLBACK_GENRES) :
        QueryPartFilter("Género", options)

    class YearsFilter(options: Array<Pair<String, String>> = FALLBACK_YEARS) :
        QueryPartFilter("Año", options)

    class LettersFilter(options: Array<Pair<String, String>> = FALLBACK_LETTERS) :
        QueryPartFilter("Letra", options)

    // ── Dynamic filter cache ────────────────────────────────────────────

    private var cachedFilters: AnimeFilterList? = null

    fun getFilterList(): AnimeFilterList = cachedFilters ?: AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        CategoriesFilter(),
        GenresFilter(),
        YearsFilter(),
        LettersFilter(),
    )

    fun fetchFilters(client: OkHttpClient, headers: Headers, baseUrl: String) {
        try {
            val document = client.newCall(GET("$baseUrl/doramas", headers)).execute().asJsoup()

            val categories = document.extractOptions("select[name=categoria] option")
                .ifEmpty { document.extractOptions("[data-name=categoria] li") }
                .ifEmpty { FALLBACK_CATEGORIES }

            val genres = document.extractOptions("select[name=genero] option")
                .ifEmpty { document.extractOptions("[data-name=genero] li") }
                .ifEmpty { FALLBACK_GENRES }

            val years = document.extractOptions("select[name=fecha] option")
                .ifEmpty { document.extractOptions("[data-name=fecha] li") }
                .ifEmpty { FALLBACK_YEARS }

            val letters = document.extractOptions("select[name=letra] option")
                .ifEmpty { document.extractOptions("[data-name=letra] li") }
                .ifEmpty { FALLBACK_LETTERS }

            cachedFilters = AnimeFilterList(
                AnimeFilter.Header("La busqueda por texto ignora el filtro"),
                CategoriesFilter(categories),
                GenresFilter(genres),
                YearsFilter(years),
                LettersFilter(letters),
            )
        } catch (_: Exception) {
            // Fallback filters are used via getFilterList()
        }
    }

    // ── Helper: extract option pairs from a document ────────────────────

    private fun Document.extractOptions(selector: String): Array<Pair<String, String>> {
        return select(selector).mapNotNull { el ->
            val value = el.attr("value").ifBlank { el.attr("data-value") }.trim()
            val name = el.text().trim()
            if (value.isNotBlank() && name.isNotBlank() && !name.startsWith("<")) {
                Pair(name, value)
            } else {
                null
            }
        }.toTypedArray()
    }

    // ── Hardcoded fallback data ─────────────────────────────────────────

    private val FALLBACK_CATEGORIES = arrayOf(
        Pair("Dorama", "dorama"),
        Pair("Live Action", "live-action"),
        Pair("Pelicula", "pelicula"),
        Pair("Series Turcas", "serie-turcas"),
    )

    private val FALLBACK_YEARS = (1982..2025).map { Pair("$it", "$it") }.reversed().toTypedArray()

    private val FALLBACK_LETTERS = ('A'..'Z').map { Pair("$it", "$it") }.toTypedArray()

    private val FALLBACK_GENRES = arrayOf(
        Pair("Acción", "accion"),
        Pair("Amistad", "amistad"),
        Pair("Aventuras", "aventuras"),
        Pair("Artes marciales", "artes-marciales"),
        Pair("Bélico", "belico"),
        Pair("C-Drama", "c-drama"),
        Pair("Ciencia Ficción", "ciencia-ficcion"),
        Pair("Comedia", "comedia"),
        Pair("Comida", "comida"),
        Pair("Crimen", "crimen"),
        Pair("Deporte", "deporte"),
        Pair("Documental", "documental"),
        Pair("Drama", "drama"),
        Pair("Escolar", "escolar"),
        Pair("Familiar", "familiar"),
        Pair("Fantasia", "fantasia"),
        Pair("Histórico", "historico"),
        Pair("HK-Drama", "hk-drama"),
        Pair("Horror", "horror"),
        Pair("Idols", "idols"),
        Pair("J-Drama", "j-drama"),
        Pair("Juvenil", "juvenil"),
        Pair("K-Drama", "k-drama"),
        Pair("Legal", "legal"),
        Pair("Médico", "medico"),
        Pair("Melodrama", "melodrama"),
        Pair("Misterio", "misterio"),
        Pair("Militar", "militar"),
        Pair("Musical", "musical"),
        Pair("Negocios", "negocios"),
        Pair("Policial", "policial"),
        Pair("Política", "politica"),
        Pair("Psicológico", "psicologico"),
        Pair("Reality Show", "reality-show"),
        Pair("Recuentos de la vida", "recuentos-de-la-vida"),
        Pair("Romance", "romance"),
        Pair("Sobrenatural", "sobrenatural"),
        Pair("Supervivencia", "supervivencia"),
        Pair("Suspenso", "suspenso"),
        Pair("Thai-Drama", "thai-drama"),
        Pair("Thriller", "thriller"),
        Pair("Time Travel", "time-travel"),
        Pair("Turcas", "turcas"),
        Pair("TW-Drama", "tw-drama"),
        Pair("Yaoi", "yaoi"),
        Pair("Yuri", "yuri"),
    )
}
