package app.bestnote.feature.onboarding

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.canvas.PageCanvasView
import app.bestnote.engine.ink.InkPalette
import app.bestnote.engine.stylus.StylusAction
import app.bestnote.engine.stylus.StylusEventMapper

/**
 * ONB-01: the first-run flow. Four screens (welcome, pen check, palm test, start), skippable, shown once.
 * Full-screen over the app; [onFinished] gets `true` when the user asked for a first notebook.
 * The caller creates the notebook (feature modules never call each other).
 */
@Composable
fun OnboardingFlow(onFinished: (createNotebook: Boolean) -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Pen down on the scratch canvas: nothing in the flow animates until it lifts.
    var writing by remember { mutableStateOf(false) }
    val reduced = BestNoteTheme.reducedMotion
    val animate = !reduced && !writing
    val finish: (Boolean) -> Unit = { create ->
        viewModel.finish()
        onFinished(create)
    }
    BackHandler(enabled = !state.isFirst) { viewModel.back() }

    Box(
        Modifier
            .fillMaxSize()
            .background(BestNoteTheme.colors.deskBrush())
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = ContentMaxWidth).fillMaxHeight().padding(horizontal = Spacing.XL),
        ) {
            Row(Modifier.fillMaxWidth().height(Spacing.TouchTarget).padding(top = Spacing.S), horizontalArrangement = Arrangement.End) {
                if (!state.isLast) {
                    TextButton(onClick = { finish(false) }) { Text(stringResource(R.string.onb_skip)) }
                }
            }
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val enter = if (animate) Motion.Enter.content(false) else EnterTransition.None
                    val exit = if (animate) Motion.Exit.content(false) else ExitTransition.None
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "onboarding-step",
            ) { step ->
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    when (step) {
                        OnboardingStep.Welcome -> WelcomePage()
                        OnboardingStep.PenCheck -> PenCheckPage(state.pen, viewModel::onContact, viewModel::chooseNoPen)
                        OnboardingStep.PalmTest -> PalmTestPage(state.pen.fingersDraw) { writing = it }
                        OnboardingStep.Start -> StartPage()
                    }
                }
            }
            BottomBar(state, animate, onBack = viewModel::back, onNext = viewModel::next, onFinish = finish)
        }
    }
}

private val ContentMaxWidth = 640.dp

// --- pages ---------------------------------------------------------------------------------------

@Composable
private fun WelcomePage() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = Spacing.L)) {
        Text(
            stringResource(R.string.onb_welcome_title),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.M))
        Text(
            stringResource(R.string.onb_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = BestNoteTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.XL))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            FactRow(Icons.Outlined.AutoStories, R.string.onb_fact_free_title, R.string.onb_fact_free_body)
            FactRow(Icons.Outlined.Code, R.string.onb_fact_open_title, R.string.onb_fact_open_body)
            FactRow(Icons.Outlined.Lock, R.string.onb_fact_private_title, R.string.onb_fact_private_body)
            FactRow(Icons.Outlined.WifiOff, R.string.onb_fact_offline_title, R.string.onb_fact_offline_body)
        }
    }
}

@Composable
private fun FactRow(icon: ImageVector, title: Int, body: Int) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
            IconBadge(icon)
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
            }
        }
    }
}

@Composable
private fun PenCheckPage(pen: PenCheckState, onContact: (Contact) -> Unit, onNoPen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = Spacing.L)) {
        Title(R.string.onb_pen_title)
        Spacer(Modifier.height(Spacing.S))
        Body(R.string.onb_pen_body)
        Spacer(Modifier.height(Spacing.XL))
        PenPad(pen, onContact)
        Spacer(Modifier.height(Spacing.L))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            Card(Modifier.weight(1f)) { Rule(Icons.Outlined.Draw, R.string.onb_rule_pen_title, R.string.onb_rule_pen_body) }
            Card(Modifier.weight(1f)) { Rule(Icons.Outlined.PanTool, R.string.onb_rule_finger_title, R.string.onb_rule_finger_body) }
        }
        if (pen.offerNoPen) {
            Spacer(Modifier.height(Spacing.M))
            OutlinedButton(onClick = onNoPen) { Text(stringResource(R.string.onb_no_pen)) }
        }
        if (pen.noPen && !pen.penSeen) {
            Spacer(Modifier.height(Spacing.M))
            Text(
                stringResource(R.string.onb_no_pen_done),
                style = MaterialTheme.typography.bodyMedium,
                color = BestNoteTheme.colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The touch pad: classifies every pointer-down with the app's own [StylusEventMapper] and shows what it saw. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun PenPad(pen: PenCheckState, onContact: (Contact) -> Unit) {
    val colors = BestNoteTheme.colors
    val detected = pen.last == Contact.Pen
    val tint = if (pen.penSeen) colors.success else MaterialTheme.colorScheme.primary
    val (icon, label) = when {
        pen.penSeen && pen.last == Contact.Finger -> Icons.Outlined.TouchApp to R.string.onb_pad_finger_after_pen
        pen.penSeen -> Icons.Outlined.CheckCircle to R.string.onb_pad_pen
        pen.last == Contact.Finger -> Icons.Outlined.TouchApp to R.string.onb_pad_finger
        else -> Icons.Outlined.Draw to R.string.onb_pad_waiting
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .softShadow(Elevation.Low, BestNoteShapes.Card)
            .clip(BestNoteShapes.Card)
            .background(MaterialTheme.colorScheme.surface)
            .border(Elevation.Hairline, if (detected) colors.success else MaterialTheme.colorScheme.outlineVariant, BestNoteShapes.Card)
            .pointerInteropFilter { event ->
                for (e in StylusEventMapper.map(event)) {
                    if (e.action == StylusAction.Down) onContact(contactOf(e.tool))
                }
                true
            }
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.XL)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(Spacing.S))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Rule(icon: ImageVector, title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
    }
}

/** A real [PageCanvasView]: the same palm rejection and ink pipeline as the editor. */
@Composable
private fun PalmTestPage(fingersDraw: Boolean, onWriting: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = Spacing.L)) {
        Title(if (fingersDraw) R.string.onb_palm_title_finger else R.string.onb_palm_title)
        Spacer(Modifier.height(Spacing.S))
        Body(if (fingersDraw) R.string.onb_palm_body_finger else R.string.onb_palm_body)
        Spacer(Modifier.height(Spacing.L))
        var canvas by remember { mutableStateOf<PageCanvasView?>(null) }
        val page = BestNoteTheme.colors.paper.page
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .softShadow(Elevation.Low, BestNoteShapes.Card)
                .clip(BestNoteShapes.Card)
                .background(page)
                .border(Elevation.Hairline, MaterialTheme.colorScheme.outlineVariant, BestNoteShapes.Card),
        ) {
            AndroidView(
                factory = { context ->
                    PageCanvasView(context).apply {
                        template = PaperTemplate.Ruled
                        colorArgb = InkPalette.BLACK
                        writingListener = PageCanvasView.WritingListener { onWriting(it) }
                        canvas = this
                    }
                },
                update = { it.stylusOnly = !fingersDraw },
                onRelease = { it.writingListener = null; onWriting(false) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(Spacing.S))
        TextButton(onClick = { canvas?.clear() }) { Text(stringResource(R.string.onb_palm_clear)) }
    }
}

@Composable
private fun StartPage() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = Spacing.L)) {
        IconBadge(Icons.Outlined.CheckCircle, size = 72.dp)
        Spacer(Modifier.height(Spacing.XL))
        Title(R.string.onb_start_title)
        Spacer(Modifier.height(Spacing.S))
        Body(R.string.onb_start_body)
    }
}

// --- chrome --------------------------------------------------------------------------------------

@Composable
private fun BottomBar(
    state: OnboardingState,
    animate: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.L), horizontalAlignment = Alignment.CenterHorizontally) {
        Dots(state.step, animate)
        Spacer(Modifier.height(Spacing.L))
        if (state.isLast) {
            Button(
                onClick = { onFinish(true) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.TouchTarget),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BestNoteTheme.colors.strongButton,
                    contentColor = BestNoteTheme.colors.onStrongButton,
                ),
            ) { Text(stringResource(R.string.onb_start_create)) }
            TextButton(onClick = { onFinish(false) }) { Text(stringResource(R.string.onb_start_library)) }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!state.isFirst) {
                    TextButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = Spacing.TouchTarget)) {
                        Text(stringResource(R.string.onb_back))
                    }
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.TouchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BestNoteTheme.colors.strongButton,
                        contentColor = BestNoteTheme.colors.onStrongButton,
                    ),
                ) {
                    Text(stringResource(R.string.onb_next))
                    Spacer(Modifier.size(Spacing.S))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Dots(current: OnboardingStep, animate: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        OnboardingStep.entries.forEach { step ->
            val on = step == current
            val target = if (on) 1f else 0f
            val grow by animateFloatAsState(target, if (animate) Motion.Spring.snappy() else Motion.tween(Motion.Duration.Instant), label = "dot")
            Box(
                Modifier
                    .size(width = Spacing.S + Spacing.L * grow, height = Spacing.S)
                    .clip(BestNoteShapes.Pill)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}

@Composable
private fun Title(res: Int) = Text(
    stringResource(res),
    style = MaterialTheme.typography.headlineMedium,
    color = MaterialTheme.colorScheme.onBackground,
    textAlign = TextAlign.Center,
)

@Composable
private fun Body(res: Int) = Text(
    stringResource(res),
    style = MaterialTheme.typography.bodyLarge,
    color = BestNoteTheme.colors.muted,
    textAlign = TextAlign.Center,
)

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .softShadow(Elevation.Low, BestNoteShapes.Card)
            .clip(BestNoteShapes.Card)
            .background(MaterialTheme.colorScheme.surface)
            .border(Elevation.Hairline, MaterialTheme.colorScheme.outlineVariant, BestNoteShapes.Card)
            .padding(Spacing.L),
    ) { content() }
}

@Composable
private fun IconBadge(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(BestNoteShapes.Pill).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
    }
}

