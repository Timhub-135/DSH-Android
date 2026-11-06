package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #58: cache hit rate next to token usage, and the todo dock folding away once the list is done. */
class TurnStatsTest {
    @Test fun cacheHitRateIsCacheReadOverTheWholePrompt() {
        // dsh's counts are disjoint: input = uncached + cacheRead + cacheWrite.
        assertEquals(0.75, Usage(uncached = 200, cacheRead = 750, cacheWrite = 50, output = 10).cacheHitRate!!, 1e-9)
        assertEquals(0.8, cacheHitRate(cacheRead = 800, input = 1000)!!, 1e-9)
    }

    @Test fun noCacheDataMeansNoRate() {
        assertNull(Usage(uncached = 1000, output = 10).cacheHitRate)
        assertNull(Usage().cacheHitRate)
        assertNull(cacheHitRate(cacheRead = 0, input = 1000))
        assertNull(cacheHitRate(cacheRead = 5, input = 0))
    }

    @Test fun aCacheWriteWithoutHitsIsZeroPercent() {
        assertEquals(0.0, Usage(uncached = 100, cacheWrite = 900).cacheHitRate!!, 1e-9)
    }

    @Test fun usageFromEngineJsonCarriesTheRate() {
        val u = Usage.of(RpcJson.parseToJsonElement("""{"inputTokens":100,"outputTokens":5,"cacheReadTokens":300}""") as JsonObject)!!
        assertEquals(0.75, u.cacheHitRate!!, 1e-9)
    }

    private fun items(vararg s: String) = s.mapIndexed { i, st -> TodoItem("t$i", st) }

    @Test fun todoDockShowsOpenListsAndFoldsFinishedOnes() {
        assertEquals(TodoDockMode.Hidden, todoDockMode(null, true))
        assertEquals(TodoDockMode.Hidden, todoDockMode(emptyList(), true))
        assertEquals(TodoDockMode.Open, todoDockMode(items("completed", "in_progress", "pending"), true))
        // Work left from an earlier turn stays visible.
        assertEquals(TodoDockMode.Open, todoDockMode(items("completed", "pending"), false))
        // Finished in this turn: one 全部完成 line; finished in an earlier turn: gone.
        assertEquals(TodoDockMode.AllDone, todoDockMode(items("completed", "completed"), true))
        assertEquals(TodoDockMode.Hidden, todoDockMode(items("completed", "completed"), false))
    }
}
