package org.vhsfever.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files approved from a batch must reach the shelf too, not only files from the older catalog. */
class CombinedFeedsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    // Both sets of saved decisions together, as they are in the real decisions.json.
    private val decisions = JSONObject(res("decisions-sample.json")).also { all ->
        val extra = JSONObject(res("decisions-batch-sample.json")).getJSONObject("files")
        val files = all.getJSONObject("files")
        for (k in extra.keys()) files.put(k, extra.get(k))
    }.toString()

    private val homeAlone = "vhsf-program-4a12bb405921ccf2"   // approved from a batch (dumbo-2001-vhs)

    @Test fun approvedBatchFileShowsUp() {
        val c = Catalog.build(listOf({ res("feed-sample.json") }, { res("batch-feed-sample.json") }), decisions)
        val ids = c.titles.flatMap { it.tapes }.map { it.fileId }
        assertTrue(homeAlone in ids)
        assertEquals(151, ids.size)   // the 150 from the older catalog plus Home Alone
        assertTrue(c.titles.any { it.name.contains("Home Alone") })
    }

    @Test fun olderCatalogAloneMissesIt() {
        val c = Catalog.build(res("feed-sample.json"), decisions)
        assertTrue(c.titles.flatMap { it.tapes }.none { it.fileId == homeAlone })
    }

    @Test fun aFileInBothFeedsIsOnlyAddedOnce() {
        val c = Catalog.build(listOf({ res("feed-sample.json") }, { res("feed-sample.json") }), decisions)
        assertEquals(150, c.titles.sumOf { it.tapes.size })
    }
}
