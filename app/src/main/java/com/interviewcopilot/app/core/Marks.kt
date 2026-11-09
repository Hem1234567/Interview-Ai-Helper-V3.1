package com.interviewcopilot.app.core

import kotlin.math.abs
import kotlin.math.round

enum class EduLevel(val key: String, val label: String) {
    Tenth("10th", "10th marksheet"),
    Twelfth("12th", "12th marksheet"),
    College("college", "College marksheet"),
    ;

    companion object {
        fun fromKey(key: String): EduLevel? = values().firstOrNull { it.key == key }
    }
}

data class SubjectMark(val name: String, val obtained: Double, val max: Double)

/** Everything read from one marksheet. All numbers can be edited by the person after reading. */
data class Marksheet(
    val level: EduLevel,
    val subjects: List<SubjectMark> = emptyList(),
    val printedTotal: Double? = null,
    val printedMax: Double? = null,
    val printedPercent: Double? = null,
    val cgpa: Double? = null,
    /** How a CGPA turns into a percentage: "x9.5" (CBSE), "x10" (Anna University and many others), "vtu". */
    val cgpaFormula: String = "x9.5",
    val institution: String = "",
    val fileName: String = "",
    /** "AI" when a vision model read the page, "OCR" when the on-device text reader did. */
    val source: String = "",
    val warnings: List<String> = emptyList(),
)

data class PercentResult(val value: Double, val basis: String)

object MarksMath {
    fun round2(v: Double): Double = round(v * 100.0) / 100.0

    fun totalObtained(s: List<SubjectMark>): Double = s.sumOf { it.obtained }

    fun totalMax(s: List<SubjectMark>): Double = s.sumOf { it.max }

    fun cgpaToPercent(cgpa: Double, formula: String): Double = when (formula) {
        "x10" -> cgpa * 10.0
        "vtu" -> (cgpa - 0.75) * 10.0
        else -> cgpa * 9.5
    }

    /** Percentage over the best [n] subjects by percentage scored (used for 12th admissions). Null with fewer than [n]. */
    fun bestOf(s: List<SubjectMark>, n: Int): Double? {
        val usable = s.filter { it.max > 0 }
        if (usable.size <= n) return null
        val best = usable.sortedByDescending { it.obtained / it.max }.take(n)
        return round2(totalObtained(best) / totalMax(best) * 100.0)
    }

    /**
     * The percentage to show. Subjects are used when they agree with the printed total, or when nothing else exists.
     * If they disagree (a mark was misread) the printed total is trusted instead, and the sheet carries a warning.
     * College sheets prefer the printed percentage, then the CGPA.
     */
    fun percent(m: Marksheet): PercentResult? {
        val subjectsTotal = totalObtained(m.subjects)
        val subjectsMax = totalMax(m.subjects)
        val fromSubjects = if (subjectsMax > 0) round2(subjectsTotal / subjectsMax * 100.0) else null
        val pt = m.printedTotal
        val pm = m.printedMax
        val fromPrinted = if (pt != null && pm != null && pm > 0) round2(pt / pm * 100.0) else null
        val agree = pt == null || abs(subjectsTotal - pt) < 0.5
        val fromCgpa = m.cgpa?.let { round2(cgpaToPercent(it, m.cgpaFormula)) }

        if (m.level == EduLevel.College) {
            m.printedPercent?.let { return PercentResult(it, "printed on the sheet") }
            if (fromCgpa != null) return PercentResult(fromCgpa, "from CGPA (${formulaLabel(m.cgpaFormula)})")
            if (fromPrinted != null) return PercentResult(fromPrinted, "from the printed total")
            if (fromSubjects != null) return PercentResult(fromSubjects, "from the subject marks")
            return null
        }
        if (fromSubjects != null && agree) return PercentResult(fromSubjects, "from the subject marks")
        if (fromPrinted != null) return PercentResult(fromPrinted, "from the printed total")
        if (fromSubjects != null) return PercentResult(fromSubjects, "from the subject marks")
        m.printedPercent?.let { return PercentResult(it, "printed on the sheet") }
        return null
    }

    fun formulaLabel(f: String): String = when (f) {
        "x10" -> "CGPA x 10"
        "vtu" -> "(CGPA - 0.75) x 10"
        else -> "CGPA x 9.5"
    }

    /** One short line per marksheet for the answer prompt, e.g. "10th: 92.4% (462/500)". */
    fun summary(m: Marksheet): String {
        val p = percent(m) ?: return ""
        val label = when (m.level) {
            EduLevel.Tenth -> "10th"
            EduLevel.Twelfth -> "12th"
            EduLevel.College -> "College"
        }
        val extra = buildString {
            if (m.level == EduLevel.College && m.cgpa != null) append(", CGPA ${fmt(m.cgpa)}")
            val t = totalObtained(m.subjects)
            val x = totalMax(m.subjects)
            if (m.level != EduLevel.College && x > 0) append(" (${fmt(t)}/${fmt(x)})")
        }
        return "$label: ${fmt(p.value)}%$extra"
    }

    fun fmt(v: Double): String =
        if (v == round(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
}

/**
 * Turns the text rows read from a marksheet (OCR or any other source) into subjects and totals.
 * Marksheets differ a lot, so this is a set of rules that agree with each other:
 * a subject row is a name followed by numbers; "theory + practical = total" picks the total column;
 * a stray subject code is dropped; marks written in words are understood; printed totals are cross-checked.
 */
object MarksParser {
    private val standardMax = setOf(20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 75.0, 80.0, 100.0, 150.0, 200.0)

    private val nonSubjectWords = setOf(
        "ROLL",
        "SCHOOL",
        "BOARD",
        "CERTIFICATE",
        "DATE",
        "RESULT",
        "PERCENTAGE",
        "PERCENT",
        "CGPA",
        "SGPA",
        "GPA",
        "CREDIT",
        "CREDITS",
        "MAXIMUM",
        "OBTAINED",
        "YEAR",
        "MONTH",
        "PASSING",
        "REGISTER",
        "SEAT",
        "SERIAL",
        "FATHER",
        "MOTHER",
        "ADDRESS",
        "DISTRICT",
        "PHONE",
        "SIGNATURE",
        "CONTROLLER",
        "PRINCIPAL",
        "EXAMINATION",
        "CANDIDATE",
        "SEMESTER",
        "PAGE",
        "ISSUED",
        "SUBJECT",
        "SUBJECTS",
        "CODE",
        "GRADE",
        "REMARKS",
        "STATUS",
        "AGGREGATE",
        "UNIVERSITY",
        "INSTITUTE",
        "COLLEGE",
    )

    private val digitWords = mapOf(
        "ZERO" to 0,
        "ONE" to 1,
        "TWO" to 2,
        "THREE" to 3,
        "FOUR" to 4,
        "FIVE" to 5,
        "SIX" to 6,
        "SEVEN" to 7,
        "EIGHT" to 8,
        "NINE" to 9,
    )
    private val teenWords = mapOf(
        "TEN" to 10,
        "ELEVEN" to 11,
        "TWELVE" to 12,
        "THIRTEEN" to 13,
        "FOURTEEN" to 14,
        "FIFTEEN" to 15,
        "SIXTEEN" to 16,
        "SEVENTEEN" to 17,
        "EIGHTEEN" to 18,
        "NINETEEN" to 19,
    )
    private val tensWords = mapOf(
        "TWENTY" to 20,
        "THIRTY" to 30,
        "FORTY" to 40,
        "FIFTY" to 50,
        "SIXTY" to 60,
        "SEVENTY" to 70,
        "EIGHTY" to 80,
        "NINETY" to 90,
    )
    private val smallWords = setOf("OF", "AND", "THE", "IN", "TO", "FOR")
    private val spaces = Regex("\\s+")
    private val plainNumber = Regex("""^\d{1,3}(\.\d{1,2})?$""")
    private val totalNumber = Regex("""^\d{1,4}(\.\d{1,2})?$""")
    private val slashMarks = Regex("""(\d{1,3}(?:\.\d{1,2})?)\s*/\s*(\d{2,3})""")

    fun parse(level: EduLevel, rows: List<String>): Marksheet {
        val subjects = ArrayList<SubjectMark>()
        var printedTotal: Double? = null
        var printedMax: Double? = null
        var printedPercent: Double? = null
        var cgpa: Double? = null
        val sgpa = ArrayList<Double>()
        var maxFirst = false

        for (raw in rows) {
            val row = raw.trim()
            if (row.isEmpty()) continue
            val up = row.uppercase()

            if (up.contains("MAX") && (up.contains("OBTAINED") || up.contains("SECURED"))) {
                val mi = up.indexOf("MAX")
                val oi = listOf(up.indexOf("OBTAINED"), up.indexOf("SECURED")).filter { it >= 0 }.minOrNull() ?: -1
                if (oi >= 0) maxFirst = mi < oi
                continue
            }

            when {
                isTotalRow(up) -> {
                    val nums = numbersIn(row, totalNumber)
                    if (nums.isNotEmpty() && printedTotal == null) {
                        val last = nums.last()
                        if (nums.size >= 2 && last % 50.0 == 0.0 && nums[nums.size - 2] <= last) {
                            printedTotal = nums[nums.size - 2]
                            printedMax = last
                        } else {
                            printedTotal = last
                        }
                    }
                }

                up.contains("PERCENT") || up.contains('%') -> {
                    val p = percentIn(row)
                    if (p != null && printedPercent == null) printedPercent = p
                }

                up.contains("CGPA") || up.contains("C.G.P.A") -> {
                    val g = gpaIn(row)
                    if (g != null && cgpa == null) cgpa = g
                }

                up.contains("SGPA") || Regex("\\bGPA\\b").containsMatchIn(up) -> {
                    gpaIn(row)?.let { sgpa.add(it) }
                }

                else -> {
                    parseSubjectRow(row, maxFirst)?.let { s ->
                        if (subjects.none { it.name.equals(s.name, ignoreCase = true) && it.obtained == s.obtained }) subjects.add(s)
                    }
                }
            }
        }
        if (cgpa == null && sgpa.isNotEmpty()) cgpa = MarksMath.round2(sgpa.average())

        var sheet = Marksheet(
            level = level,
            subjects = subjects,
            printedTotal = printedTotal,
            printedMax = printedMax ?: if (printedTotal != null && subjects.isNotEmpty()) MarksMath.totalMax(subjects) else null,
            printedPercent = printedPercent,
            cgpa = cgpa,
            source = "OCR",
        )
        sheet = sheet.copy(warnings = check(sheet))
        return sheet
    }

    /** Cross-checks; the messages are shown to the person next to the marks. */
    fun check(m: Marksheet): List<String> {
        val out = ArrayList<String>()
        if (m.subjects.isEmpty() && m.cgpa == null && m.printedPercent == null && m.printedTotal == null) {
            out.add("No marks could be read from this file. Add the subjects below, or upload a clearer photo or PDF.")
            return out
        }
        val sum = MarksMath.totalObtained(m.subjects)
        val pt = m.printedTotal
        if (pt != null && m.subjects.isNotEmpty() && abs(sum - pt) >= 0.5) {
            out.add("The subject marks add up to ${MarksMath.fmt(sum)} but the sheet prints ${MarksMath.fmt(pt)}. Check the marks below.")
        }
        val pp = m.printedPercent
        val calc = MarksMath.percent(m)
        if (pp != null && calc != null && m.level != EduLevel.College && abs(calc.value - pp) > 0.6) {
            out.add("The sheet prints ${MarksMath.fmt(pp)}% but the marks give ${MarksMath.fmt(calc.value)}%. Check the marks below.")
        }
        if (m.subjects.isEmpty() && m.level != EduLevel.College) {
            out.add("Subject marks were not found. Add them below to see the percentage.")
        }
        return out
    }

    // ---------- rows ----------

    private fun isTotalRow(up: String): Boolean =
        up.startsWith("TOTAL") || up.startsWith("GRAND") || up.startsWith("AGGREGATE") ||
            up.contains("GRAND TOTAL") || up.contains("TOTAL MARKS") || up.contains("TOTAL OBTAINED")

    private fun numbersIn(row: String, shape: Regex): List<Double> =
        row.split(spaces).mapNotNull { t -> fixDigits(t.trim(',', ':', ';')).takeIf { shape.matches(it) }?.toDoubleOrNull() }

    private fun percentIn(row: String): Double? {
        val cands = row.split(spaces).mapNotNull { t ->
            val clean = fixDigits(t.trim(',', ':', ';')).removeSuffix("%")
            val v = if (Regex("""^\d{1,3}(\.\d{1,3})?$""").matches(clean)) clean.toDoubleOrNull() else null
            if (v != null && v <= 100.0) v to (t.contains('.') || t.contains('%')) else null
        }
        return (cands.firstOrNull { it.second } ?: cands.lastOrNull())?.first
    }

    private fun gpaIn(row: String): Double? {
        val cands = row.split(spaces).mapNotNull { t ->
            val clean = fixDigits(t.trim(',', ':', ';')).substringBefore('/')
            val v = if (Regex("""^\d{1,2}(\.\d{1,3})?$""").matches(clean)) clean.toDoubleOrNull() else null
            if (v != null && v in 0.1..10.0) v to t.contains('.') else null
        }
        return (cands.firstOrNull { it.second } ?: cands.firstOrNull())?.first
    }

    /** OCR often reads 0 as O and 1 as l or I inside numbers. Only touches tokens that already contain a digit. */
    internal fun fixDigits(t: String): String {
        if (t.none { it.isDigit() }) return t
        if (!t.all { it.isDigit() || it in "OolI|.,/%" }) return t
        return t.map {
            when (it) {
                'O', 'o' -> '0'
                'I', 'l', '|' -> '1'
                else -> it
            }
        }.joinToString("")
    }

    private fun isNumberLike(t: String): Boolean {
        val f = fixDigits(t.trim(',', ':', ';'))
        return f.any { it.isDigit() } && f.all { it.isDigit() || it == '.' || it == '/' || it == '%' }
    }

    private fun isCodeToken(t: String): Boolean {
        if (t.length > 10 || t.none { it.isDigit() }) return false
        val core = t.trim('.', ')', '(', ':', ',')
        return core.isNotEmpty() && core.all { it.isLetterOrDigit() } && (core.all { it.isDigit() } || core.any { it.isLetter() })
    }

    private fun isNameToken(t: String): Boolean =
        t == "&" || t == "-" || (t.any { it.isLetter() } && t.all { it.isLetter() || it in "&-.()/,'" } && !isNumberLike(t))

    internal fun parseSubjectRow(row: String, maxFirst: Boolean): SubjectMark? {
        val tokens = row.split(spaces).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null

        var i = 0
        while (i < tokens.size && isCodeToken(tokens[i]) && i < 2) i++
        val nameStart = i
        while (i < tokens.size && isNameToken(tokens[i])) i++
        var nameEnd = i
        if (nameEnd - nameStart < 1) return null
        var rest = tokens.subList(nameEnd, tokens.size)

        // "85/100" written as one token
        var nums: List<Double> = emptyList()
        var forcedMax: Double? = null
        val slash = slashMarks.find(rest.joinToString(" "))
        if (slash != null) {
            val o = slash.groupValues[1].toDoubleOrNull()
            val m = slash.groupValues[2].toDoubleOrNull()
            if (o != null && m != null && m in standardMax && o <= m) {
                nums = listOf(o)
                forcedMax = m
            }
        }
        if (nums.isEmpty()) {
            nums = rest.mapNotNull { t -> fixDigits(t.trim(',', ':', ';')).takeIf { plainNumber.matches(it) }?.toDoubleOrNull() }
        }

        // No digits: marks written in words, e.g. "ENGLISH EIGHTY FIVE A1" or "MATHS NINE ZERO".
        if (nums.isEmpty()) {
            val words = tokens.subList(nameStart, tokens.size)
            var best: Pair<Int, Int>? = null
            for (start in (nameStart + 1) until tokens.size) {
                var end = tokens.size
                while (end > start) {
                    val v = wordsToNumber(tokens.subList(start, end))
                    if (v != null && v in 0..200) {
                        best = start to v
                        break
                    }
                    end--
                }
                if (best != null) break
            }
            if (words.isEmpty() || best == null) return null
            nameEnd = best.first
            nums = listOf(best.second.toDouble())
            rest = emptyList()
        }

        val pair = pickMarks(nums, maxFirst, forcedMax) ?: return null
        val name = cleanName(tokens.subList(nameStart, nameEnd))
        if (name.count { it.isLetter() } < 3) return null
        if (name.uppercase().split(' ', '.', ',').any { it in nonSubjectWords }) return null
        if (pair.first < 0 || pair.first > pair.second) return null
        return SubjectMark(name, pair.first, pair.second)
    }

    /** Chooses (obtained, max) from the numbers that follow a subject name. */
    internal fun pickMarks(nums: List<Double>, maxFirst: Boolean, forcedMax: Double?): Pair<Double, Double>? {
        if (nums.isEmpty()) return null
        if (forcedMax != null) return nums[0] to forcedMax

        // theory + practical = total
        for (k in 0..nums.size - 3) {
            if (abs(nums[k] + nums[k + 1] - nums[k + 2]) < 0.01 && nums[k + 2] <= 200.0) {
                val max = nums.drop(k + 3).firstOrNull { it in standardMax && it >= nums[k + 2] } ?: defaultMax(nums[k + 2])
                return nums[k + 2] to max
            }
        }
        if (nums.size >= 2) {
            val a = nums[nums.size - 2]
            val b = nums.last()
            if (maxFirst && a in standardMax && b <= a) return b to a
            if (b in standardMax && a <= b && (a != b || a == 100.0)) return a to b
        }
        if (nums.size == 1) {
            val n = nums[0]
            return if (n <= 100.0) n to 100.0 else null
        }
        // Several numbers and no pattern: a subject code, credits or grade points may be mixed in.
        val plausible = nums.filter { it <= 100.0 }
        val pick = plausible.maxOrNull() ?: return null
        return pick to 100.0
    }

    private fun defaultMax(total: Double): Double = if (total <= 100.0) 100.0 else standardMax.firstOrNull { it >= total } ?: total

    internal fun wordsToNumber(tokens: List<String>): Int? {
        val w = tokens.map { it.uppercase().trim('.', ',', ':', ';') }.filter { it != "AND" }
        if (w.isEmpty()) return null
        val known = digitWords.keys + teenWords.keys + tensWords.keys + "HUNDRED"
        if (!w.all { it in known }) return null
        if (w.size >= 2 && w.all { it in digitWords && it != "HUNDRED" }) {
            return w.fold(0) { acc, t -> acc * 10 + digitWords.getValue(t) }
        }
        var cur = 0
        for (t in w) {
            when {
                t in digitWords -> cur += digitWords.getValue(t)
                t in teenWords -> cur += teenWords.getValue(t)
                t in tensWords -> cur += tensWords.getValue(t)
                t == "HUNDRED" -> cur = (if (cur == 0) 1 else cur) * 100
            }
        }
        return cur
    }

    private fun cleanName(tokens: List<String>): String {
        val joined = tokens.joinToString(" ").trim(' ', '.', ',', '-', ':', '/')
        return joined.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { t ->
            val letters = t.filter { it.isLetter() }
            when {
                letters.isEmpty() -> t
                t.uppercase() in smallWords -> t.lowercase()
                letters.length <= 3 && letters == letters.uppercase() -> t
                letters == letters.uppercase() || letters == letters.lowercase() -> t.lowercase().replaceFirstChar { it.uppercase() }
                else -> t
            }
        }.replaceFirstChar { it.uppercase() }
    }
}

/** A line of text with the box it occupies on the page, as returned by a text reader. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = maxOf(1, bottom - top)
}

object RowBuilder {
    /**
     * Rebuilds table rows from loose text lines. A text reader tends to return every table column as its own
     * block; lines whose vertical centres are close belong to the same printed row and are joined left to right.
     */
    fun rows(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.centerY }
        val heights = sorted.map { it.height }.sorted()
        val tolerance = heights[heights.size / 2] * 0.6
        val groups = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val g = groups.lastOrNull()
            if (g != null && abs(l.centerY - g.map { it.centerY }.average()) <= tolerance) g.add(l) else groups.add(mutableListOf(l))
        }
        return groups.map { g -> g.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim() }
    }
}
