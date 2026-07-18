package eu.kanade.tachiyomi.animeextension.es.veranimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

object VerAnimesFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString(",").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name=$it"
                }
            }
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") { fun getQuery() = filter.changePrefix() }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(FILTER_LIST_FALLBACK.genresOptions, "genero") +
                filters.parseCheckbox<YearsFilter>(FILTER_LIST_FALLBACK.yearsOptions, "anio") +
                filters.parseCheckbox<TypesFilter>(FILTER_LIST_FALLBACK.typesOptions, "tipo") +
                filters.asQueryPart<StateFilter>("estado") +
                filters.asQueryPart<SortFilter>("orden"),
        )
    }

    fun getFilterList(): AnimeFilterList = cachedFilters ?: FILTER_LIST_FALLBACK.list

    class GenresFilter(options: Array<Pair<String, String>>) : CheckBoxFilterList("Género", options.map { CheckBoxVal(it.first, false) })

    class YearsFilter(options: Array<Pair<String, String>>) : CheckBoxFilterList("Año", options.map { CheckBoxVal(it.first, false) })

    class TypesFilter(options: Array<Pair<String, String>>) : CheckBoxFilterList("Tipo", options.map { CheckBoxVal(it.first, false) })

    class StateFilter(options: Array<Pair<String, String>>) : QueryPartFilter("Estado", options)

    class SortFilter(options: Array<Pair<String, String>>) : QueryPartFilter("Orden", options)

    private val SEASONAL_REGEX = Regex("^(invierno|primavera|verano|otono)-\\d{4}$")

    private fun Document.selectOptions(selector: String): Array<Pair<String, String>> {
        return select(selector).mapNotNull { li ->
            val value = li.attr("data-value")
            val name = li.text().trim()
            if (value.isBlank() || name.isBlank()) null else Pair(name, value)
        }.filter { (_, value) -> !SEASONAL_REGEX.matches(value) }
            .toTypedArray()
    }

    private data class FilterOptions(
        val genres: Array<Pair<String, String>>,
        val years: Array<Pair<String, String>>,
        val types: Array<Pair<String, String>>,
        val state: Array<Pair<String, String>>,
        val sort: Array<Pair<String, String>>,
    ) {
        val list: AnimeFilterList
            get() = AnimeFilterList(
                AnimeFilter.Header("La busqueda por texto ignora el filtro"),
                GenresFilter(genres),
                YearsFilter(years),
                TypesFilter(types),
                StateFilter(state),
                SortFilter(sort),
            )

        val genresOptions get() = genres
        val yearsOptions get() = years
        val typesOptions get() = types
    }

    private object FallbackData {
        val YEARS = (1967..2024).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val TYPES = arrayOf(
            Pair("Tv", "tv"),
            Pair("Película", "pelicula"),
            Pair("Especial", "especial"),
            Pair("Ova", "ova"),
        )

        val STATE = arrayOf(
            Pair("Todos", ""),
            Pair("En Emisión", "en-emision"),
            Pair("Finalizado", "finalizado"),
            Pair("Próximamente", "proximamente"),
        )

        val SORT = arrayOf(
            Pair("Descendente", "desc"),
            Pair("Ascendente", "asc"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventuras", "aventuras"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }

    private val FILTER_LIST_FALLBACK = FilterOptions(
        genres = FallbackData.GENRES,
        years = FallbackData.YEARS,
        types = FallbackData.TYPES,
        state = FallbackData.STATE,
        sort = FallbackData.SORT,
    )

    var cachedFilters: AnimeFilterList? = null
        private set

    suspend fun fetchFilters(client: OkHttpClient, headers: Headers, baseUrl: String): AnimeFilterList {
        val document = client.newCall(GET("$baseUrl/animes", headers)).execute().asJsoup()

        val options = FilterOptions(
            genres = document.selectOptions("#filter .fil-select:has(span[data-name=genero]) ul li"),
            years = document.selectOptions("#filter .fil-select:has(span[data-name=anio]) ul li"),
            types = document.selectOptions("#filter .fil-select[data-type=check]:has(span[data-name=tipo]) ul li"),
            state = document.selectOptions("#filter .fil-select[data-type=radio]:has(span[data-name=estado]) ul li"),
            sort = document.selectOptions("#filter .fil-select[data-type=radio]:has(span[data-name=orden]) ul li"),
        )

        val list = options.list
        cachedFilters = list
        return list
    }
}
