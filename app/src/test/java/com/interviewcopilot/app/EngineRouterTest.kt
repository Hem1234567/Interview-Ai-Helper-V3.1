package com.interviewcopilot.app

import com.interviewcopilot.app.core.Candidate
import com.interviewcopilot.app.core.Engine
import com.interviewcopilot.app.core.FailureKind
import com.interviewcopilot.app.core.Health
import com.interviewcopilot.app.core.Models
import com.interviewcopilot.app.core.ProviderFailure
import com.interviewcopilot.app.core.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRouterTest {
    private val cloud = mapOf(
        Engine.Groq to listOf("g1", "g2"),
        Engine.Google to listOf("m1"),
        Engine.OpenRouter to listOf("openrouter/free"),
    )

    private fun names(l: List<Candidate>) = l.map { it.engine.name + ":" + it.model }

    @Test
    fun autoTriesCloudFastestFirstAndEndsOnLocal() {
        val plan = RoutePlanner.plan(Engine.Auto, true, cloud, true, Health())
        assertEquals(listOf("Groq:g1", "Groq:g2", "Google:m1", "OpenRouter:openrouter/free", "Local:"), names(plan))
    }

    @Test
    fun autoSkipsEnginesWithoutKeys() {
        val plan = RoutePlanner.plan(Engine.Auto, true, mapOf(Engine.Google to listOf("m1")), true, Health())
        assertEquals(listOf("Google:m1", "Local:"), names(plan))
    }

    @Test
    fun rateLimitedEngineMovesBehindTheLocalModel() {
        var now = 0L
        val h = Health { now }
        h.fail("Groq:g1", ProviderFailure(FailureKind.RateLimit, 429, "limit"))
        h.fail("Groq:g2", ProviderFailure(FailureKind.RateLimit, 429, "limit"))
        val plan = RoutePlanner.plan(Engine.Auto, true, cloud, true, h)
        assertEquals(listOf("Google:m1", "OpenRouter:openrouter/free", "Local:", "Groq:g1", "Groq:g2"), names(plan))
        now += 61_000L
        assertFalse(h.cooling("Groq:g1"))
        assertEquals("Groq:g1", names(RoutePlanner.plan(Engine.Auto, true, cloud, true, h)).first())
    }

    @Test
    fun allCloudDownGoesToLocalFirst() {
        val h = Health { 0L }
        cloud.forEach { (e, ms) -> ms.forEach { h.fail("${e.name}:$it", ProviderFailure(FailureKind.BadKey, 401, "bad")) } }
        assertEquals("Local:", names(RoutePlanner.plan(Engine.Auto, true, cloud, true, h)).first())
    }

    @Test
    fun chosenEngineGoesFirstAndFallbackFollows() {
        val plan = RoutePlanner.plan(Engine.Google, true, cloud, true, Health())
        assertEquals(listOf("Google:m1", "Groq:g1", "Groq:g2", "OpenRouter:openrouter/free", "Local:"), names(plan))
    }

    @Test
    fun chosenEngineAloneWhenFallbackIsOff() {
        assertEquals(listOf("Groq:g1", "Groq:g2"), names(RoutePlanner.plan(Engine.Groq, false, cloud, true, Health())))
        assertTrue(RoutePlanner.plan(Engine.Local, false, cloud, false, Health()).isEmpty())
    }

    @Test
    fun localOnlyWhenLocalChosen() {
        assertEquals(listOf("Local:"), names(RoutePlanner.plan(Engine.Local, false, cloud, true, Health())))
    }

    @Test
    fun budgetSkipsCloudOnlyWhenLocalCanAnswer() {
        val c = Candidate(Engine.Google, "m1")
        assertFalse(RoutePlanner.skipCloud(c, 1_000, true))
        assertTrue(RoutePlanner.skipCloud(c, 4_200, true))
        assertFalse(RoutePlanner.skipCloud(c, 4_200, false))
        assertFalse(RoutePlanner.skipCloud(Candidate(Engine.Local, ""), 9_000, true))
        assertTrue(RoutePlanner.timeoutFor(c, 3_000) <= 1_500L)
        assertEquals(2_500L, RoutePlanner.timeoutFor(Candidate(Engine.Groq, "g"), 0))
    }

    @Test
    fun httpCodesMapToFailureKinds() {
        assertEquals(FailureKind.RateLimit, ProviderFailure.fromHttp("Groq", 429, "", "12").kind)
        assertEquals(12_000L, ProviderFailure.fromHttp("Groq", 429, "", "12").retryAfterMs)
        assertEquals(FailureKind.BadKey, ProviderFailure.fromHttp("Google", 403, "", null).kind)
        assertEquals(FailureKind.ModelGone, ProviderFailure.fromHttp("Google", 404, "", null).kind)
        assertEquals(FailureKind.Server, ProviderFailure.fromHttp("OpenRouter", 503, "", null).kind)
    }

    @Test
    fun savedSettingsAndModelListsAreParsed() {
        assertEquals(Engine.Google, Engine.from("Gemini"))
        assertEquals(Engine.Groq, Engine.from("Groq"))
        assertEquals(Engine.Auto, Engine.from(""))
        assertEquals(listOf("a", "b", "c"), Models.parse(" a, b ;c,,a"))
    }
}
