package app.bestnote.engine.stylus

import app.bestnote.engine.stylus.PointerEventSource.Companion.CURRENT

/** One fake pointer; [history] holds (x, y, pressure) triples before the current sample. */
data class FakePointer(
    val id: Int,
    val toolType: Int,
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val history: List<Triple<Float, Float, Float>> = emptyList(),
)

class FakePointerEvent(
    override val actionMasked: Int,
    private val pointers: List<FakePointer>,
    override val actionIndex: Int = 0,
    override val buttonState: Int = 0,
    override val flags: Int = 0,
    private val time: Long = 1_000L,
) : PointerEventSource {
    override val pointerCount get() = pointers.size
    override val historySize get() = pointers.first().history.size
    override fun pointerId(index: Int) = pointers[index].id
    override fun toolType(index: Int) = pointers[index].toolType
    override fun x(index: Int, historyPos: Int) = if (historyPos == CURRENT) pointers[index].x else pointers[index].history[historyPos].first
    override fun y(index: Int, historyPos: Int) = if (historyPos == CURRENT) pointers[index].y else pointers[index].history[historyPos].second
    override fun pressure(index: Int, historyPos: Int) = if (historyPos == CURRENT) pointers[index].pressure else pointers[index].history[historyPos].third
    override fun tilt(index: Int, historyPos: Int) = pointers[index].tilt
    override fun orientation(index: Int, historyPos: Int) = pointers[index].orientation
    override fun timeMillis(historyPos: Int) = if (historyPos == CURRENT) time else time - (historySize - historyPos) * 4L
}
