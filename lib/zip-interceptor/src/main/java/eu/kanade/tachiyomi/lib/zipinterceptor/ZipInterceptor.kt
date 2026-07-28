package eu.kanade.tachiyomi.lib.zipinterceptor

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Method
import java.util.zip.ZipInputStream

object ImageDecoderWrapper {
    private var decodeMethod: Method
    private var newInstanceMethod: Method
    private var classSignature = ClassSignature.Newest

    private enum class ClassSignature {
        Old,
        New,
        Newest,
    }

    init {
        val rectClass = Rect::class.java
        val booleanClass = Boolean::class.java
        val intClass = Int::class.java
        val byteArrayClass = ByteArray::class.java
        val inputStreamClass = InputStream::class.java

        try {
            classSignature = ClassSignature.Newest

            decodeMethod = ImageDecoder::class.java.getMethod(
                "decode",
                rectClass,
                intClass,
            )

            newInstanceMethod = ImageDecoder.Companion::class.java.getMethod(
                "newInstance",
                inputStreamClass,
                booleanClass,
                byteArrayClass,
            )
        } catch (_: NoSuchMethodException) {
            try {
                classSignature = ClassSignature.New

                decodeMethod = ImageDecoder::class.java.getMethod(
                    "decode",
                    rectClass,
                    booleanClass,
                    intClass,
                    booleanClass,
                    byteArrayClass,
                )

                newInstanceMethod = ImageDecoder.Companion::class.java.getMethod(
                    "newInstance",
                    inputStreamClass,
                    booleanClass,
                )
            } catch (_: NoSuchMethodException) {
                classSignature = ClassSignature.Old

                decodeMethod =
                    ImageDecoder::class.java.getMethod(
                        "decode",
                        rectClass,
                        booleanClass,
                        intClass,
                    )

                newInstanceMethod = ImageDecoder.Companion::class.java.getMethod(
                    "newInstance",
                    inputStreamClass,
                    booleanClass,
                )
            }
        }
    }

    fun decodeImage(data: ByteArray, rgb565: Boolean, filename: String, entryName: String): Bitmap {
        val decoder = when (classSignature) {
            ClassSignature.Newest -> newInstanceMethod.invoke(ImageDecoder.Companion, ByteArrayInputStream(data), false, null)
            else -> newInstanceMethod.invoke(ImageDecoder.Companion, ByteArrayInputStream(data), false)
        } as ImageDecoder?

        if (decoder == null || decoder.width <= 0 || decoder.height <= 0) {
            throw IOException("Failed to initialize image decoder")
        }

        val rect = Rect(0, 0, decoder.width, decoder.height)
        val bitmap = when (classSignature) {
            ClassSignature.Newest -> decodeMethod.invoke(decoder, rect, 1)
            ClassSignature.New -> decodeMethod.invoke(decoder, rect, rgb565, 1, false, null)
            else -> decodeMethod.invoke(decoder, rect, rgb565, 1)
        } as Bitmap?

        decoder.recycle()

        if (bitmap == null) {
            throw IOException("Could not decode image $filename#$entryName")
        }

        return bitmap
    }
}

open class ZipInterceptor {
    private val dataUriRegex = Regex("""base64,([0-9a-zA-Z/+=\s]+)""")

    open fun zipGetByteStream(request: Request, response: Response): InputStream = response.body.byteStream()

    open fun requestIsZipImage(request: Request): Boolean = request.url.fragment == "page" && request.url.pathSegments.last().contains(".zip")

    fun zipImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val filename = request.url.pathSegments.last()

        if (requestIsZipImage(request).not()) {
            return response
        }

        val zis = ZipInputStream(zipGetByteStream(request, response))

        val images = generateSequence { zis.nextEntry }
            .mapNotNull {
                val entryName = it.name
                val splitEntryName = entryName.split('.')
                val entryIndex = splitEntryName.first().toIntOrNull() ?: return@mapNotNull null
                val entryType = splitEntryName.last()

                val imageData = if (entryType == "avif" || splitEntryName.size == 1) {
                    zis.readBytes()
                } else {
                    val svgBytes = zis.readBytes()
                    val svgContent = svgBytes.toString(Charsets.UTF_8)
                    val b64 = dataUriRegex.find(svgContent)?.groupValues?.get(1)
                        ?: return@mapNotNull null

                    Base64.decode(b64, Base64.DEFAULT)
                }

                entryIndex to ImageDecoderWrapper.decodeImage(imageData, isLowRamDevice, filename, entryName)
            }
            .sortedBy { it.first }
            .toList()

        zis.closeEntry()
        zis.close()

        if (images.isEmpty()) {
            return response
        }

        val totalWidth = images.maxOf { it.second.width }
        val totalHeight = images.sumOf { it.second.height }

        val config = if (isLowRamDevice) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val result = Bitmap.createBitmap(totalWidth, totalHeight, config)
        val canvas = Canvas(result)

        var dy = 0

        images.forEach {
            val srcRect = Rect(0, 0, it.second.width, it.second.height)
            val dstRect = Rect(0, dy, it.second.width, dy + it.second.height)

            canvas.drawBitmap(it.second, srcRect, dstRect, null)

            dy += it.second.height
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 90, output)

        val image = output.toByteArray()
        val body = image.toResponseBody("image/jpeg".toMediaType())

        return response.newBuilder()
            .body(body)
            .build()
    }

    private val isLowRamDevice by lazy {
        val application = Injekt.get<Application>()
        val activityManager = application.getSystemService(ActivityManager::class.java) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()

        activityManager.getMemoryInfo(memInfo)

        memInfo.totalMem < 3L * 1024 * 1024 * 1024
    }
}
