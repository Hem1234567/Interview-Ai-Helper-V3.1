package com.interviewcopilot.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.Marksheet
import com.interviewcopilot.app.core.SubjectMark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QA(
    val id: String,
    val session: String,
    val question: String,
    val answer: String,
    val time: Long,
    val mode: String,
    val favorite: Boolean = false,
)

data class JobProfile(
    val id: String,
    val title: String,
    val description: String,
    val keywords: String = "",
    val active: Boolean = false,
)

object Defaults {
    val map: Map<String, String> = mapOf(
        "provider" to "Auto",
        "fallback" to "true",
        "orKey" to "",
        // Several models can be listed, separated by commas. They are tried in order, so one that was retired
        // or is rate limited does not stop the answer.
        "orModel" to "openrouter/free",
        "geminiKey" to "",
        "geminiModel" to "gemini-3.1-flash-lite, gemini-2.5-flash-lite, gemini-2.5-flash",
        "groqKey" to "",
        "groqModel" to "openai/gpt-oss-20b, openai/gpt-oss-120b",
        "localModelId" to "",
        "whisperModelId" to "whisper-base",
        "sttEngine" to "Auto",
        "sttLang" to "auto",
        "memory" to "false",
        "theme" to "System",
        "style" to "Concise",
        "speed" to "Fast",
        "mode" to "Interview",
        "resume" to "",
        "vocab" to "",
        "pscores" to "",
    )
}

class Store(private val ctx: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx,
            "secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("prefs_fallback", Context.MODE_PRIVATE)
    }

    private val historyFile get() = File(ctx.filesDir, "history.json")
    private val jobsFile get() = File(ctx.filesDir, "jobs.json")

    fun all(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in prefs.all) {
            if (v is String) out[k] = v
        }
        return out
    }

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun loadHistory(): List<QA> {
        return try {
            if (!historyFile.exists()) return emptyList()
            val arr = JSONArray(historyFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QA(
                    id = o.getString("id"),
                    session = o.getString("session"),
                    question = o.getString("question"),
                    answer = o.getString("answer"),
                    time = o.getLong("time"),
                    mode = o.optString("mode", "Interview"),
                    favorite = o.optBoolean("favorite", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHistory(list: List<QA>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("session", it.session)
                        .put("question", it.question)
                        .put("answer", it.answer)
                        .put("time", it.time)
                        .put("mode", it.mode)
                        .put("favorite", it.favorite),
                )
            }
            historyFile.writeText(arr.toString())
        } catch (ignored: Exception) {
        }
    }

    fun loadJobs(): List<JobProfile> {
        return try {
            if (!jobsFile.exists()) return emptyList()
            val arr = JSONArray(jobsFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                JobProfile(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    description = o.getString("description"),
                    keywords = o.optString("keywords", ""),
                    active = o.optBoolean("active", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveJobs(list: List<JobProfile>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("description", it.description)
                        .put("keywords", it.keywords)
                        .put("active", it.active),
                )
            }
            jobsFile.writeText(arr.toString())
        } catch (ignored: Exception) {
        }
    }

    /** Marksheets read in Settings > Education, keyed by [EduLevel.key]. Stored in the encrypted preferences. */
    fun loadMarks(): Map<String, Marksheet> {
        return try {
            val raw = prefs.getString("marks_v1", "") ?: ""
            if (raw.isBlank()) return emptyMap()
            val root = JSONObject(raw)
            val out = LinkedHashMap<String, Marksheet>()
            for (level in EduLevel.values()) {
                val o = root.optJSONObject(level.key) ?: continue
                val subs = o.optJSONArray("subjects") ?: JSONArray()
                val list = (0 until subs.length()).map { i ->
                    val so = subs.getJSONObject(i)
                    SubjectMark(so.getString("name"), so.getDouble("obtained"), so.getDouble("max"))
                }
                val warns = o.optJSONArray("warnings") ?: JSONArray()
                out[level.key] = Marksheet(
                    level = level,
                    subjects = list,
                    printedTotal = o.optDouble("printedTotal", Double.NaN).takeUnless { it.isNaN() },
                    printedMax = o.optDouble("printedMax", Double.NaN).takeUnless { it.isNaN() },
                    printedPercent = o.optDouble("printedPercent", Double.NaN).takeUnless { it.isNaN() },
                    cgpa = o.optDouble("cgpa", Double.NaN).takeUnless { it.isNaN() },
                    cgpaFormula = o.optString("cgpaFormula", "x9.5"),
                    institution = o.optString("institution", ""),
                    fileName = o.optString("fileName", ""),
                    source = o.optString("source", ""),
                    warnings = (0 until warns.length()).map { warns.getString(it) },
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveMarks(all: Map<String, Marksheet>) {
        try {
            val root = JSONObject()
            for ((key, m) in all) {
                val subs = JSONArray()
                m.subjects.forEach { subs.put(JSONObject().put("name", it.name).put("obtained", it.obtained).put("max", it.max)) }
                val warns = JSONArray()
                m.warnings.forEach { warns.put(it) }
                val o = JSONObject()
                    .put("subjects", subs)
                    .put("cgpaFormula", m.cgpaFormula)
                    .put("institution", m.institution)
                    .put("fileName", m.fileName)
                    .put("source", m.source)
                    .put("warnings", warns)
                m.printedTotal?.let { o.put("printedTotal", it) }
                m.printedMax?.let { o.put("printedMax", it) }
                m.printedPercent?.let { o.put("printedPercent", it) }
                m.cgpa?.let { o.put("cgpa", it) }
                root.put(key, o)
            }
            prefs.edit().putString("marks_v1", root.toString()).apply()
        } catch (ignored: Exception) {
        }
    }

    fun wipe() {
        prefs.edit().clear().apply()
        historyFile.delete()
        jobsFile.delete()
    }
}
