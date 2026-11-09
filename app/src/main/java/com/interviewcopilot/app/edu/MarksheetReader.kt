package com.interviewcopilot.app.edu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.MarksParser
import com.interviewcopilot.app.core.Marksheet
import com.interviewcopilot.app.core.OcrLine
import com.interviewcopilot.app.core.RowBuilder
import com.interviewcopilot.app.core.SubjectMark
import com.interviewcopilot.app.logic.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads a marksheet (PDF, photo or scan) and returns the marks.
 *
 * 1. Every page is turned into an image (PDF pages are rendered, photos are decoded with their rotation fixed).
 * 2. If a Google API key is set, a Gemini vision model reads the page. It copes best with unusual layouts.
 * 3. Otherwise, or if that fails, the on-device text reader (ML Kit) reads the words with their positions,
 *    the rows of the table are rebuilt, and [MarksParser] picks out the subjects, totals, percentage and CGPA.
 */
object MarksheetReader {
    private const val MAX_PAGES = 6
    private const val PDF_WIDTH_PX = 1800
    private const val MAX_IMAGE_SIDE = 2600

    suspend fun read(ctx: Context, uri: Uri, level: EduLevel, googleKey: String, googleModels: List<String>): Marksheet {
        val name = displayName(ctx, uri)
        val pages = withContext(Dispatchers.IO) { loadPages(ctx, uri, name) }
        if (pages.isEmpty()) throw IOException("This file could not be opened. Use a PDF, JPG or PNG.")
        try {
            if (googleKey.isNotBlank()) {
                val ai = try {
                    readWithVision(pages, level, googleKey, googleModels)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (ai != null && (ai.subjects.isNotEmpty() || ai.cgpa != null || ai.printedPercent != null)) {
                    return ai.copy(fileName = name, warnings = MarksParser.check(ai))
                }
            }
            val rows = ArrayList<String>()
            for (page in pages) rows.addAll(RowBuilder.rows(recognise(page)))
            return MarksParser.parse(level, rows).copy(fileName = name)
        } finally {
            pages.forEach { it.recycle() }
        }
    }

    // ---------- pages ----------

    private fun displayName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "marksheet"
        } catch (e: Exception) {
            "marksheet"
        }
    }

    private fun loadPages(ctx: Context, uri: Uri, name: String): List<Bitmap> {
        val type = ctx.contentResolver.getType(uri) ?: ""
        return if (type == "application/pdf" || name.lowercase().endsWith(".pdf")) renderPdf(ctx, uri) else listOfNotNull(decodeImage(ctx, uri))
    }

    private fun renderPdf(ctx: Context, uri: Uri): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return out
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PAGES)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val h = (page.height.toFloat() / page.width * PDF_WIDTH_PX).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(PDF_WIDTH_PX, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE) // PDF pages are transparent; the text reader needs a white page
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp)
                    }
                }
            }
        }
        return out
    }

    private fun decodeImage(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software bitmaps can be read by the text reader; the decoder also applies the photo's rotation.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_IMAGE_SIDE) {
                    val scale = MAX_IMAGE_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- on-device text reading ----------

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    private suspend fun recognise(page: Bitmap): List<OcrLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = recognizer.process(InputImage.fromBitmap(page, 0)).awaitResult()
            val lines = ArrayList<OcrLine>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    lines.add(OcrLine(line.text, box.left, box.top, box.right, box.bottom))
                }
            }
            return lines
        } finally {
            recognizer.close()
        }
    }

    // ---------- vision model ----------

    private fun prompt(level: EduLevel): String {
        val kind = when (level) {
            EduLevel.Tenth -> "10th standard (SSLC / CBSE / ICSE) marksheet"
            EduLevel.Twelfth -> "12th standard (HSC / Plus Two / CBSE / ISC) marksheet"
            EduLevel.College -> "college or university marksheet or grade card"
        }
        return "This is a $kind. Read it and answer with JSON only, in exactly this shape: " +
            "{\"institution\":string,\"subjects\":[{\"name\":string,\"obtained\":number,\"max\":number}]," +
            "\"totalObtained\":number|null,\"totalMax\":number|null,\"percentage\":number|null,\"cgpa\":number|null}. " +
            "Rules: copy only numbers that are printed. \"obtained\" is the subject's TOTAL marks (theory plus practical or internal plus external), " +
            "never the subject code, credits, roll number or grade points. \"max\" is the maximum for that subject (usually 100). " +
            "If marks appear only in words, convert them to digits. Include every subject, and do not include the student's name or numbers. " +
            "Use null for anything that is not printed. For college sheets with several semesters list every subject once and give the overall CGPA."
    }

    private suspend fun readWithVision(pages: List<Bitmap>, level: EduLevel, key: String, models: List<String>): Marksheet? {
        val parts = JSONArray().put(JSONObject().put("text", prompt(level)))
        for (p in pages.take(3)) {
            val out = ByteArrayOutputStream()
            p.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", data)))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
            .toString()
        val client = Http.client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
        for (model in models.ifEmpty { listOf("gemini-3.1-flash-lite") }) {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                        .addHeader("x-goog-api-key", key)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            null
                        } else {
                            val root = JSONObject(resp.body?.string().orEmpty())
                            val reply = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                                .getJSONArray("parts").getJSONObject(0).getString("text")
                            toMarksheet(level, JSONObject(reply.trim().removePrefix("```json").removeSuffix("```").trim()))
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun num(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key, Double.NaN).takeUnless { it.isNaN() }

    private fun toMarksheet(level: EduLevel, o: JSONObject): Marksheet {
        val arr = o.optJSONArray("subjects") ?: JSONArray()
        val subjects = ArrayList<SubjectMark>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val name = s.optString("name", "").trim()
            val obtained = num(s, "obtained") ?: continue
            val max = num(s, "max") ?: 100.0
            if (name.isNotEmpty() && obtained >= 0 && max > 0 && obtained <= max) subjects.add(SubjectMark(name, obtained, max))
        }
        val total = num(o, "totalObtained")
        val totalMax = num(o, "totalMax")
        return Marksheet(
            level = level,
            subjects = subjects,
            printedTotal = total,
            printedMax = totalMax,
            printedPercent = num(o, "percentage")?.takeIf { it in 0.0..100.0 },
            cgpa = num(o, "cgpa")?.takeIf { it in 0.0..10.0 },
            institution = o.optString("institution", "").take(80),
            source = "AI",
        )
    }
}
