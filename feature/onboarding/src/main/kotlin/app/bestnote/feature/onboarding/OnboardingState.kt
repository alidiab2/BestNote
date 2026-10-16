package app.bestnote.feature.onboarding

import app.bestnote.engine.stylus.PointerTool

/** The four screens of the first-run flow (ONB-01), in order. */
enum class OnboardingStep { Welcome, PenCheck, PalmTest, Start }

/** What touched the pen-check pad. */
enum class Contact { Pen, Finger, Other }

/** The S Pen / USI stylus (and its eraser end) is a pen; a finger is a finger; mice and unknown tools are neither. */
fun contactOf(tool: PointerTool): Contact = when (tool) {
    PointerTool.Stylus, PointerTool.Eraser -> Contact.Pen
    PointerTool.Finger -> Contact.Finger
    PointerTool.Mouse, PointerTool.Unknown -> Contact.Other
}

/** What the first-run flow changes in the existing stylus settings when it ends (nothing new is invented). */
enum class PenOutcome {
    /** A pen touched the pad: remember it ([app.bestnote.core.datastore.StylusPreferences.penSeen]), so fingers only scroll and zoom. */
    PenSeen,

    /** "I don't have a pen": fingers always draw ([app.bestnote.core.datastore.FingerDrawing.Always]). */
    FingerDrawing,

    /** Nothing learned: keep the defaults (auto mode). */
    Unchanged,
}

/** Live result of the pen check. Pure, so the classification is unit-tested. */
data class PenCheckState(
    val penSeen: Boolean = false,
    val fingerTouches: Int = 0,
    val last: Contact? = null,
    val noPen: Boolean = false,
) {
    fun onContact(contact: Contact): PenCheckState = when (contact) {
        Contact.Pen -> copy(penSeen = true, last = contact)
        Contact.Finger -> copy(fingerTouches = fingerTouches + 1, last = contact)
        Contact.Other -> this
    }

    /** After a finger touch and no pen at all, offer "I don't have a pen". */
    val offerNoPen: Boolean get() = !penSeen && !noPen && fingerTouches > 0

    fun chooseNoPen(): PenCheckState = if (penSeen) this else copy(noPen = true)

    /** A pen seen beats an earlier "no pen" answer. */
    val outcome: PenOutcome
        get() = when {
            penSeen -> PenOutcome.PenSeen
            noPen -> PenOutcome.FingerDrawing
            else -> PenOutcome.Unchanged
        }

    /** The palm test uses a finger canvas when the user has no pen. */
    val fingersDraw: Boolean get() = noPen && !penSeen
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val pen: PenCheckState = PenCheckState(),
) {
    val isFirst: Boolean get() = step == OnboardingStep.entries.first()
    val isLast: Boolean get() = step == OnboardingStep.entries.last()

    fun next(): OnboardingState = if (isLast) this else copy(step = OnboardingStep.entries[step.ordinal + 1])

    fun back(): OnboardingState = if (isFirst) this else copy(step = OnboardingStep.entries[step.ordinal - 1])

    fun onContact(contact: Contact): OnboardingState = copy(pen = pen.onContact(contact))

    fun chooseNoPen(): OnboardingState = copy(pen = pen.chooseNoPen())
}
