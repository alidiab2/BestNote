package app.bestnote.engine.layout

import app.bestnote.core.model.RectPt
import org.junit.Assert.assertEquals
import org.junit.Test

class LectureFlowTest {
    private fun item(key: String, box: RectPt? = null, order: Int? = null, anchor: String? = null) =
        FlowItem(key, if (anchor != null) RegionRole.Margin else RegionRole.Main, anchorKey = anchor, order = order, sourceBox = box)

    private fun keys(items: List<FlowItem>) = items.map { it.key }

    @Test fun `explicit lecture order beats board position`() {
        val items = listOf(
            item("example", RectPt(0f, 0f, 0.3f, 0.2f), order = 2),
            item("definition", RectPt(0.6f, 0.7f, 0.9f, 0.9f), order = 0),
            item("formula", RectPt(0.4f, 0.1f, 0.5f, 0.2f), order = 1),
        )
        assertEquals(listOf("definition", "formula", "example"), keys(LectureFlow.order(items)))
    }

    @Test fun `unordered items stay next to their neighbour`() {
        val items = listOf(item("b", order = 5), item("b2"), item("a", order = 1))
        assertEquals(listOf("a", "b", "b2"), keys(LectureFlow.order(items)))
    }

    @Test fun `without order the board is read panel by panel`() {
        // Two panels: left panel top-to-bottom, then right panel, under a full-width title line.
        val items = listOf(
            item("right-top", RectPt(0.55f, 0.2f, 0.95f, 0.3f)),
            item("left-bottom", RectPt(0.05f, 0.6f, 0.45f, 0.7f)),
            item("heading", RectPt(0.05f, 0.02f, 0.95f, 0.1f)),
            item("left-top", RectPt(0.05f, 0.2f, 0.4f, 0.3f)),
            item("right-bottom", RectPt(0.6f, 0.5f, 0.9f, 0.6f)),
        )
        assertEquals(
            listOf("heading", "left-top", "left-bottom", "right-top", "right-bottom"),
            keys(LectureFlow.order(items)),
        )
    }

    @Test fun `rtl boards read the right panel first`() {
        val items = listOf(item("left", RectPt(0.05f, 0.2f, 0.4f, 0.3f)), item("right", RectPt(0.6f, 0.2f, 0.95f, 0.3f)))
        assertEquals(listOf("right", "left"), keys(LectureFlow.order(items, rtl = true)))
    }

    @Test fun `margin notes follow the block they annotate and the title leads`() {
        val items = listOf(
            item("note", anchor = "proof"),
            item("proof", order = 1),
            item("intro", order = 0),
            FlowItem("title", RegionRole.Title, order = LectureFlow.FIRST),
        )
        assertEquals(listOf("title", "intro", "proof", "note"), keys(LectureFlow.order(items)))
    }
}
