package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOrderTest {
    @Test fun emptyOrBlankRestoresDefaultOrder() {
        assertEquals(DefaultToolOrder, toolOrder(emptyList()))
        assertEquals(DefaultToolOrder, toolOrder(listOf("", "   ")))
    }

    @Test fun customOrderIsPreserved() {
        val custom = listOf("downloader", "enhance", "framecap", "compress", "gif", "trim", "extract")
        assertEquals(custom, toolOrder(custom))
    }

    @Test fun unknownIdsAreDroppedAndMissingIdsAppendInDefaultOrder() {
        assertEquals(
            listOf("downloader", "enhance", "compress", "trim", "extract", "gif", "framecap"),
            toolOrder(listOf("unknown", "downloader", "future_tool", "enhance", "")),
        )
    }

    @Test fun duplicateIdsKeepFirstOccurrence() {
        assertEquals(
            listOf("gif", "trim", "compress", "extract", "framecap", "downloader", "enhance"),
            toolOrder(listOf("gif", "trim", "gif", "compress", "trim")),
        )
    }

    @Test fun movedToolOrderSwapsWithinBounds() {
        assertEquals(
            listOf("trim", "compress", "extract", "gif", "framecap", "downloader", "enhance"),
            movedToolOrder(DefaultToolOrder, 0, 1),
        )
        assertEquals(
            listOf("downloader", "compress", "trim", "extract", "gif", "framecap", "enhance"),
            movedToolOrder(DefaultToolOrder, 5, 0),
        )
        assertEquals(DefaultToolOrder, movedToolOrder(DefaultToolOrder, 0, -1))
        assertEquals(DefaultToolOrder, movedToolOrder(DefaultToolOrder, 6, 7))
        assertEquals(DefaultToolOrder, movedToolOrder(DefaultToolOrder, 2, 2))
    }
}
