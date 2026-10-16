package app.bestnote.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropPlanTest {
    @Test fun `picture items become pictures even when the item has no own type`() {
        val plan = DropPlan.plan(listOf(DropItem("image/png", "content://a/1", null), DropItem(null, "content://a/2", null)), listOf("image/*"))
        assertEquals(listOf(DropAction.Picture("content://a/1"), DropAction.Picture("content://a/2")), plan)
    }

    @Test fun `plain text becomes a text box and blank text is ignored`() {
        val plan = DropPlan.plan(listOf(DropItem(null, null, "  مرحبا  "), DropItem(null, null, "   ")), listOf("text/plain"))
        assertEquals(listOf(DropAction.Words("مرحبا")), plan)
    }

    @Test fun `files that are not pictures are ignored`() {
        assertTrue(DropPlan.plan(listOf(DropItem("application/pdf", "content://a/x", null)), listOf("application/pdf")).isEmpty())
    }

    @Test fun `a drop is capped`() {
        val many = (1..20).map { DropItem("image/jpeg", "content://a/$it", null) }
        assertEquals(DropPlan.MAX_ITEMS, DropPlan.plan(many, listOf("image/jpeg")).size)
    }

    @Test fun `a frame dropped near an edge stays on the page`() {
        assertEquals(50f to 40f, ElementPlacement.centeredAt(0f, 0f, 100f, 80f, 600f, 800f))
        assertEquals(550f to 760f, ElementPlacement.centeredAt(999f, 999f, 100f, 80f, 600f, 800f))
        assertEquals(300f to 400f, ElementPlacement.centeredAt(10f, 10f, 700f, 900f, 600f, 800f))
    }
}
