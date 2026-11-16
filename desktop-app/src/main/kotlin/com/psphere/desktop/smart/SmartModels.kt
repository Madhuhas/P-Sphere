package com.psphere.desktop.smart

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * The optional search models and how to get them. They're downloaded only when the user taps
 * Download in Settings; this is the only time smart search goes online. Each file is checked
 * against its SHA-256 before use, so a damaged or altered download is never loaded.
 */
object SmartModels {
    const val VISION = "vision.onnx"
    const val TEXT = "text.onnx"
    const val FACE_FIND = "face-find.onnx"
    const val FACE_KNOW = "face-know.onnx"
    const val OCR_FIND = "ocr-find.onnx"
    const val OCR_READ = "ocr-read.onnx"
    const val OCR_DICT = "ocr-dict.txt"

    /** Changes when the search model changes, so photos get scanned again with the new one. */
    const val MODEL_ID = "clip-vit-b32-q8"

    // OpenAI CLIP ViT-B/32 (MIT licence), 8-bit ONNX export, pinned to one revision.
    private const val BASE = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/"

    data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

    // OpenCV Zoo face models: YuNet (MIT licence) finds faces, SFace (Apache 2.0) tells them apart.
    private const val YUNET = "https://huggingface.co/opencv/face_detection_yunet/resolve/3cc26e7f1014a5ee5d74a42acee58bafc9d0a310/"
    private const val SFACE = "https://huggingface.co/opencv/face_recognition_sface/resolve/3d7082438a6e4551e840c9b2bb60b71e8da4b524/"
    // PaddleOCR (Apache 2.0) as ONNX: PP-OCRv3 line finder, PP-OCRv5 English reader.
    private const val PADDLE = "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/"

    val files = listOf(
        ModelFile(VISION, BASE + "onnx/vision_model_quantized.onnx", 89_117_001, "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"),
        ModelFile(TEXT, BASE + "onnx/text_model_quantized.onnx", 64_504_507, "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a"),
        ModelFile("vocab.json", BASE + "vocab.json", 862_328, "5047b556ce86ccaf6aa22b3ffccfc52d391ea4accdab9c2f2407da5b742d4363"),
        ModelFile("merges.txt", BASE + "merges.txt", 524_619, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
        ModelFile(FACE_FIND, YUNET + "face_detection_yunet_2023mar.onnx", 232_589, "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
        ModelFile(FACE_KNOW, SFACE + "face_recognition_sface_2021dec.onnx", 38_696_353, "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"),
        ModelFile(OCR_FIND, PADDLE + "detection/v3/det.onnx", 2_429_873, "ee40e80071ba3a320d4efda75f3e22047a7d049e9bf7bcaaf9daea23fc21b935"),
        ModelFile(OCR_READ, PADDLE + "languages/english/rec.onnx", 7_830_888, "4e16deb22c4da6468bdca539b2cd3c8687825538b67109177c47d359ab994cd7"),
        ModelFile(OCR_DICT, PADDLE + "languages/english/dict.txt", 1_416, "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6"),
    )

    val totalBytes: Long = files.sumOf { it.size }

    /** True when every file is there with the right size (hashes are checked at download time). */
    fun installed(dir: File): Boolean = files.all { File(dir, it.name).length() == it.size }

    /**
     * Downloads whatever is missing into [dir]. Resumes a partly downloaded file. [onProgress]
     * gets bytes done and the total. Checks [cancelled] between chunks. Throws on failure.
     */
    fun download(dir: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        dir.mkdirs()
        var doneBefore = 0L
        for (f in files) {
            val target = File(dir, f.name)
            if (target.length() == f.size) { doneBefore += f.size; onProgress(doneBefore, totalBytes); continue }
            val part = File(dir, f.name + ".part")
            if (part.length() > f.size) part.delete()
            fetch(f, part, cancelled) { n -> onProgress(doneBefore + n, totalBytes) }
            if (sha256(part) != f.sha256) {
                part.delete()
                throw IOException("A downloaded file was damaged. Please try again.")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't save ${f.name}")
            doneBefore += f.size
        }
        onProgress(totalBytes, totalBytes)
    }

    private fun fetch(f: ModelFile, part: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        var url = URI(f.url).toURL()
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "PSphere")
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                url = URI(f.url).resolve(conn.getHeaderField("Location")).toURL()
                conn.disconnect()
                continue
            }
            break
        }
        val code = conn.responseCode
        val append = code == 206
        if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code). Check your internet connection.")
        if (!append) part.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                var total = part.length()
                while (true) {
                    if (cancelled()) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }
        }
        conn.disconnect()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
