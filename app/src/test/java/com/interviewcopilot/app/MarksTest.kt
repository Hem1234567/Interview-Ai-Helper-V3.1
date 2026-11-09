package com.interviewcopilot.app

import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.MarksMath
import com.interviewcopilot.app.core.MarksParser
import com.interviewcopilot.app.core.Marksheet
import com.interviewcopilot.app.core.OcrLine
import com.interviewcopilot.app.core.RowBuilder
import com.interviewcopilot.app.core.SubjectMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksTest {
    private fun marks(m: Marksheet) = m.subjects.map { "${it.name}=${MarksMath.fmt(it.obtained)}/${MarksMath.fmt(it.max)}" }

    @Test
    fun cbseTenthWithCodeBeforeNameAndGradeAfter() {
        val rows = listOf(
            "CENTRAL BOARD OF SECONDARY EDUCATION",
            "ROLL NO 1234567 DATE OF BIRTH 12 03 2005",
            "SUB CODE SUBJECT MARKS OBTAINED POSITIONAL GRADE",
            "184 ENGLISH LNG & LIT. 088 EIGHT EIGHT A2",
            "002 HINDI COURSE-A 090 NINE ZERO A1",
            "041 MATHEMATICS STANDARD 095 NINE FIVE A1",
            "086 SCIENCE 091 NINE ONE A1",
            "087 SOCIAL SCIENCE 093 NINE THREE A1",
            "RESULT: PASS",
        )
        val m = MarksParser.parse(EduLevel.Tenth, rows)
        assertEquals(
            listOf("English LNG & LIT=88/100", "Hindi Course-a=90/100", "Mathematics Standard=95/100", "Science=91/100", "Social Science=93/100"),
            marks(m),
        )
        assertEquals(457.0, MarksMath.totalObtained(m.subjects), 0.001)
        assertEquals(91.4, MarksMath.percent(m)!!.value, 0.001)
        assertTrue(m.warnings.isEmpty())
    }

    @Test
    fun theoryPlusPracticalPicksTheTotalColumn() {
        val rows = listOf(
            "ENGLISH 184 071 018 089 EIGHT NINE B1",
            "PHYSICS 042 060 025 085 EIGHT FIVE A2",
            "GRAND TOTAL 174 200",
        )
        val m = MarksParser.parse(EduLevel.Twelfth, rows)
        assertEquals(listOf("English=89/100", "Physics=85/100"), marks(m))
        assertEquals(174.0, m.printedTotal!!, 0.001)
        assertEquals(87.0, MarksMath.percent(m)!!.value, 0.001)
    }

    @Test
    fun marksAsSlashAndMaxColumns() {
        val rows = listOf("TAMIL 85/100", "ENGLISH 78 / 100", "COMPUTER SCIENCE 92 100", "PHYSICS 45 50")
        val m = MarksParser.parse(EduLevel.Twelfth, rows)
        assertEquals(listOf("Tamil=85/100", "English=78/100", "Computer Science=92/100", "Physics=45/50"), marks(m))
    }

    @Test
    fun marksWrittenOnlyInWords() {
        val m = MarksParser.parse(EduLevel.Tenth, listOf("MATHEMATICS EIGHTY FIVE A1", "SCIENCE NINE ZERO A1", "ENGLISH ONE HUNDRED O"))
        assertEquals(listOf("Mathematics=85/100", "Science=90/100", "English=100/100"), marks(m))
    }

    @Test
    fun ocrMistakesInsideNumbersAreRepaired() {
        val m = MarksParser.parse(EduLevel.Tenth, listOf("MATHEMATICS 9O", "SCIENCE O88", "SOCIAL SCIENCE l00"))
        assertEquals(listOf("Mathematics=90/100", "Science=88/100", "Social Science=100/100"), marks(m))
    }

    @Test
    fun printedTotalIsCrossCheckedAndWarnsOnMismatch() {
        val rows = listOf("TAMIL 85", "ENGLISH 78", "MATHS 92", "SCIENCE 88", "SOCIAL 90", "TOTAL 433 500")
        val ok = MarksParser.parse(EduLevel.Tenth, rows)
        assertTrue(ok.warnings.isEmpty())
        assertEquals(86.6, MarksMath.percent(ok)!!.value, 0.001)

        val bad = MarksParser.parse(EduLevel.Tenth, rows.dropLast(1) + "TOTAL 450 500")
        assertTrue(bad.warnings.isNotEmpty())
        assertEquals(90.0, MarksMath.percent(bad)!!.value, 0.001) // the printed total wins over a misread subject
        assertEquals("from the printed total", MarksMath.percent(bad)!!.basis)
    }

    @Test
    fun collegeSheetUsesCgpaAndPrintedPercentage() {
        val rows = listOf(
            "CS101 DATA STRUCTURES 45 40 85 A 4",
            "CS102 DISCRETE MATHEMATICS 38 37 75 B 4",
            "SGPA 8.4",
            "CGPA 8.72",
        )
        val m = MarksParser.parse(EduLevel.College, rows)
        assertEquals(listOf("Data Structures=85/100", "Discrete Mathematics=75/100"), marks(m))
        assertEquals(8.72, m.cgpa!!, 0.0001)
        assertEquals(82.84, MarksMath.percent(m)!!.value, 0.001)
        assertEquals(87.2, MarksMath.percent(m.copy(cgpaFormula = "x10"))!!.value, 0.001)
        assertEquals(79.7, MarksMath.percent(m.copy(cgpaFormula = "vtu"))!!.value, 0.001)
        assertEquals(78.5, MarksMath.percent(m.copy(printedPercent = 78.5))!!.value, 0.001)
    }

    @Test
    fun nothingReadGivesAHelpfulWarning() {
        val m = MarksParser.parse(EduLevel.Tenth, listOf("HELLO", "THIS IS NOT A MARKSHEET"))
        assertTrue(m.subjects.isEmpty())
        assertNull(MarksMath.percent(m))
        assertTrue(m.warnings.first().contains("No marks"))
    }

    @Test
    fun bestOfFiveAndSummary() {
        val s = Marksheet(
            EduLevel.Twelfth,
            subjects = listOf(
                SubjectMark("A", 90.0, 100.0),
                SubjectMark("B", 80.0, 100.0),
                SubjectMark("C", 70.0, 100.0),
                SubjectMark("D", 60.0, 100.0),
                SubjectMark("E", 50.0, 100.0),
                SubjectMark("F", 95.0, 100.0),
            ),
        )
        assertEquals(79.0, MarksMath.bestOf(s.subjects, 5)!!, 0.001)
        assertEquals("12th: 74.17% (445/600)", MarksMath.summary(s))
        assertNull(MarksMath.bestOf(s.subjects.take(5), 5))
    }

    @Test
    fun rowsAreRebuiltFromScatteredOcrLines() {
        val lines = listOf(
            OcrLine("85", 400, 102, 440, 128),
            OcrLine("MATHEMATICS", 20, 100, 300, 130),
            OcrLine("SCIENCE", 20, 150, 200, 180),
            OcrLine("91", 400, 152, 440, 178),
        )
        assertEquals(listOf("MATHEMATICS 85", "SCIENCE 91"), RowBuilder.rows(lines))
    }
}
