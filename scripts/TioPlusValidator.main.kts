#!/usr/bin/env kotlinc -script
@file:DependsOn("org.jsoup:jsoup:1.18.3")

import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

val BASE = "https://tioplus.app"
val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
val TIMEOUT = 30000
val AES_KEY = "kiemtienmua911ca".toByteArray()
val AES_IV = "1234567890oiuytr".toByteArray()

data class HttpResult(val status: Int, val body: String, val url: String)
data class ServerProbe(
    val label: String,
    val redirectUrl: String,
    val status: String,
    val streamUrl: String = "",
    val evidence: String = "",
)

var failed = 0
var passed = 0

fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
        println("  [OK] $name")
    } else {
        failed++
        println("  [FAIL] $name${if (detail.isNotBlank()) " -> $detail" else ""}")
    }
}

fun connect(url: String, referer: String? = null): Connection = Jsoup.connect(url)
    .userAgent(UA)
    .timeout(TIMEOUT)
    .ignoreContentType(true)
    .ignoreHttpErrors(true)
    .followRedirects(true)
    .apply { if (referer != null) referrer(referer) }

fun fetch(path: String): Document = connect(if (path.startsWith("http")) path else BASE + path).get()

fun fetchText(url: String, referer: String? = null): HttpResult {
    val response = connect(url, referer).execute()
    return HttpResult(response.statusCode(), response.body(), response.url().toString())
}

fun decryptPelisPlusPayload(hex: String): String {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
    val clean = hex.trim()
    val bytes = ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return String(cipher.doFinal(bytes))
}

fun jsonString(json: String, key: String): String =
    Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.replace("\\/", "/")
        ?.replace("\\\"", "\"")
        .orEmpty()

fun resolvePlayer(dataServer: String): String {
    val encoded = Base64.getEncoder().encodeToString(dataServer.toByteArray())
    val playerHtml = fetchText("$BASE/player/$encoded", BASE).body
    return Regex("window\\.location\\.href\\s*=\\s*['\"]([^'\"]+)")
        .find(playerHtml)
        ?.groupValues
        ?.get(1)
        .orEmpty()
}

fun probeEmTurbo(url: String, label: String): ServerProbe {
    val body = fetchText(url, BASE).body
    val hls = Regex("urlPlay\\s*=\\s*['\"]([^'\"]+)").find(body)?.groupValues?.get(1).orEmpty()
    if (hls.isBlank()) return ServerProbe(label, url, "NO_STREAM", evidence = "urlPlay not found")
    val playlist = fetchText(hls, url)
    return if (playlist.status == 200 && playlist.body.startsWith("#EXTM3U")) {
        ServerProbe(label, url, "DIRECT_HLS", hls, "playlist status=200")
    } else {
        ServerProbe(label, url, "NO_STREAM", hls, "playlist status=${playlist.status}")
    }
}

fun probePelisPlusPlayer(url: String, label: String): ServerProbe {
    val hash = url.substringAfter("#", "").substringBefore("&")
    val origin = url.substringBefore("/#").substringBefore("#")
    if (hash.isBlank()) return ServerProbe(label, url, "NO_STREAM", evidence = "missing hash")

    fetchText(url, origin)
    fetchText("$origin/api/v1/info?id=$hash", origin)
    val videoResponse = fetchText("$origin/api/v1/video?id=$hash&w=1600&h=900&r=", origin)
    if (videoResponse.status != 200) {
        return ServerProbe(label, url, "NO_STREAM", evidence = "video endpoint status=${videoResponse.status}; body=${videoResponse.body.take(80)}")
    }

    val json = runCatching { decryptPelisPlusPayload(videoResponse.body) }.getOrElse {
        return ServerProbe(label, url, "NO_STREAM", evidence = "decrypt failed: ${it.message}")
    }
    val hls = listOf("source", "cf", "cfNative", "hlsVideoTiktok", "hlsVideoGoogle")
        .firstNotNullOfOrNull { key -> jsonString(json, key).takeIf { it.startsWith("http") } }
        .orEmpty()
    if (hls.isBlank()) return ServerProbe(label, url, "NO_STREAM", evidence = "no HLS field in decrypted JSON")

    val playlist = fetchText(hls, origin)
    return if (playlist.status == 200 && playlist.body.startsWith("#EXTM3U")) {
        ServerProbe(label, url, "DIRECT_HLS", hls, "video endpoint decrypted; playlist status=200")
    } else {
        ServerProbe(label, url, "NO_STREAM", hls, "playlist status=${playlist.status}; body=${playlist.body.take(80)}")
    }
}

fun probeServer(label: String, redirectUrl: String): ServerProbe = when {
    redirectUrl.contains("emturbovid", true) -> probeEmTurbo(redirectUrl, label)
    listOf("upns.pro", "rpmstream.live", "strp2p.com", "4meplayer.pro").any { it in redirectUrl.lowercase() } -> probePelisPlusPlayer(redirectUrl, label)
    redirectUrl.contains("vidhide", true) -> ServerProbe(label, redirectUrl, "EXTERNAL_EXTRACTOR", evidence = "handled by VidHideExtractor in extension")
    else -> ServerProbe(label, redirectUrl, "UNKNOWN", evidence = "no inline probe")
}

println("\n=== 1) Populares (/peliculas) ===")
val popular = fetch("/peliculas")
val popularArticles = popular.select("article.item > a.itemA")
check("Hay artículos en el listado", popularArticles.size >= 1, "size=${popularArticles.size}")
check("Existe enlace de paginación siguiente", popular.select("a.page[rel=next]").isNotEmpty())

println("\n=== 2) Detalle/listado/episodios ===")
val movieUrl = popularArticles.firstOrNull()?.attr("abs:href")
check("Se obtuvo URL de película", movieUrl.orEmpty().contains("/pelicula/"), movieUrl.orEmpty())
val detail = movieUrl?.let(::fetch)
if (detail != null) {
    check("Título H1 no vacío", detail.selectFirst("h1.slugh1")?.text().orEmpty().isNotBlank())
    check("Sinopsis no vacía", detail.selectFirst("div.description p")?.text().orEmpty().length > 20)
    check("Opciones de servidor presentes", detail.select("ul.subselect li[data-server]").isNotEmpty())
}

val latest = fetch("/series")
val serieUrl = latest.select("article.item > a.itemA").firstOrNull { it.attr("abs:href").contains("/serie/") }?.attr("abs:href")
check("Se obtuvo URL de serie", serieUrl.orEmpty().contains("/serie/"), serieUrl.orEmpty())
if (serieUrl != null) {
    val serie = fetch(serieUrl)
    val script = serie.selectFirst("script:containsData(const seasonsJson =)")?.data().orEmpty()
    val episodes = Regex("\"episode\"\\s*:\\s*(\\d+)").findAll(script).count()
    check("Se extraen episodios de seasonsJson", episodes > 0, "size=$episodes")
}

println("\n=== 3) Búsqueda (/api/search/<query>) ===")
val query = URLEncoder.encode("dragon", "UTF-8")
val searchResults = Jsoup.parse(fetchText("$BASE/api/search/$query", BASE).body).select("article.item > a.itemA")
check("La búsqueda devuelve resultados", searchResults.isNotEmpty(), "size=${searchResults.size}")

println("\n=== 4) Servidores de vídeo ===")
val serverRows = detail?.select("ul.subselect li[data-server]").orEmpty()
val probes = serverRows.map { row ->
    val label = row.selectFirst("span")?.text().orEmpty()
    val redirect = resolvePlayer(row.attr("data-server"))
    if (redirect.isBlank()) ServerProbe(label, "", "NO_REDIRECT") else probeServer(label, redirect)
}.toMutableList()

if (probes.none { it.redirectUrl.contains("rpmstream.live") }) {
    probes += probePelisPlusPlayer("https://pelisplus.rpmstream.live/#pncq3r", "RPM compatibility probe")
}

probes.forEach { probe ->
    val stream = if (probe.streamUrl.isBlank()) "-" else probe.streamUrl.take(120)
    println("  ${probe.label}: ${probe.status}")
    println("    redirect=${probe.redirectUrl}")
    println("    stream=$stream")
    if (probe.evidence.isNotBlank()) println("    evidence=${probe.evidence}")
}

check("Earnvids queda delegado a VidHideExtractor", probes.any { it.redirectUrl.contains("vidhide", true) && it.status == "EXTERNAL_EXTRACTOR" })
check("TioPlus/EmTurbo produce HLS directo", probes.any { it.redirectUrl.contains("emturbovid", true) && it.status == "DIRECT_HLS" })
check("UPFAST produce HLS directo", probes.any { it.redirectUrl.contains("upns.pro", true) && it.status == "DIRECT_HLS" })
check("P2P produce HLS directo", probes.any { it.redirectUrl.contains("strp2p.com", true) && it.status == "DIRECT_HLS" })
check("PLAYER produce HLS directo", probes.any { it.redirectUrl.contains("4meplayer.pro", true) && it.status == "DIRECT_HLS" })

println("\n==================================================")
println("  RESULTADO: $passed OK / $failed FAIL")
println("==================================================")
if (failed > 0) {
    kotlin.system.exitProcess(1)
}
