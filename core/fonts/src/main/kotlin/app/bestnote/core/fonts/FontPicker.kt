package app.bestnote.core.fonts

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import kotlinx.coroutines.launch
import java.io.IOException

/** A compose [FontFamily] for a registry font id (the glyph fallback chain included), or null for the default. */
@Composable
fun rememberFontFamily(fontId: String?, registry: FontRegistry = FontRegistry.get(LocalContext.current)): FontFamily? {
    val fonts by registry.fonts.collectAsState()
    return remember(fontId, fonts) { fontId?.let { id -> HandwritingTypefaces.of(id)?.let { FontFamily(it) } } }
}

private fun sampleFor(script: FontScript?): String = (script ?: FontScript.Latin).sample

@Composable
private fun scriptName(script: FontScript) = stringResource(
    when (script) {
        FontScript.Latin -> R.string.fonts_script_latin
        FontScript.Arabic -> R.string.fonts_script_arabic
        FontScript.Hebrew -> R.string.fonts_script_hebrew
    },
)

@Composable
private fun errorText(e: Throwable?): String? = when (e) {
    null -> null
    is FontRejected -> stringResource(
        when (e.kind) {
            FontRejected.Kind.NotAFont -> R.string.fonts_error_not_font
            FontRejected.Kind.Collection -> R.string.fonts_error_collection
            FontRejected.Kind.TooLarge -> R.string.fonts_error_too_large
            FontRejected.Kind.Damaged -> R.string.fonts_error_damaged
            FontRejected.Kind.NoLetters -> R.string.fonts_error_no_letters
        },
    )
    is IOException -> stringResource(R.string.fonts_error_network)
    else -> stringResource(R.string.fonts_error_damaged)
}

/**
 * Picks a font for [script] (template look) or for any script when null (text boxes, the keyboard format bar and the
 * lasso's text bar, TEXT-05). Search by name, filter by script, starred favourites and recent picks first, then the
 * fonts on this device (bundled text fonts and hands, downloaded ones) previewed in the font itself, "My fonts" with
 * "Add my own font…", and the curated Google Fonts catalogue: tapping one downloads it and picks it.
 * [selectedId] null = the default sans (shown when [allowDefault]).
 */
@Composable
fun FontPickerDialog(
    script: FontScript?,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    allowDefault: Boolean = false,
    registry: FontRegistry = FontRegistry.get(LocalContext.current),
) {
    val fonts by registry.fonts.collectAsState()
    val downloading by registry.downloading.collectAsState()
    val recents by registry.recents.collectAsState()
    val favourites by registry.favourites.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Throwable?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(script) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            registry.import(uri).onSuccess { added = it.name; error = null }.onFailure { error = it }
        }
    }
    fun pick(id: String?) {
        registry.noteUsed(id)
        onSelect(id)
    }
    fun fits(f: AppFont) = script == null || script in f.scripts
    val shown = FontChoices.filter(fonts, query, filter)
    val installed = shown.filter { it.origin != FontOrigin.Mine && fits(it) }.sortedBy { it.category.ordinal }
    val starred = installed.filter { it.id in favourites }
    val recent = recents.mapNotNull { id -> installed.firstOrNull { it.id == id } }.filter { it.id !in favourites }
    val mine = shown.filter { it.origin == FontOrigin.Mine }
    val more = FontChoices.filterCatalogue(if (script == null) FontCatalogue.all else FontCatalogue.forScript(script), query, filter)
        .filter { c -> fonts.none { it.id == c.id } }
    val sample: (AppFont) -> String = { f -> sampleFor(filter ?: script ?: f.scripts.firstOrNull()) }
    val row: @Composable (AppFont, String) -> Unit = { f, key ->
        FontRow(
            f.name, f.id, sample(f), f.id == selectedId,
            note = categoryName(f.category),
            favourite = f.id in favourites,
            onFavourite = { registry.toggleFavourite(f.id) },
        ) { pick(f.id) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = BestNoteShapes.Sheet, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Text(stringResource(R.string.fonts_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text(stringResource(R.string.fonts_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                    if (script == null) FilterChip(filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.fonts_all_scripts)) })
                    (if (script == null) FontScript.entries else listOf(script)).forEach { s ->
                        FilterChip(filter == s, onClick = { filter = if (filter == s && script == null) null else s }, label = { Text(scriptName(s)) })
                    }
                }
                LazyColumn(Modifier.heightIn(max = 460.dp).fillMaxWidth()) {
                    if (allowDefault && query.isBlank()) item("default") {
                        FontRow(stringResource(R.string.fonts_default), null, sampleFor(filter ?: script), selectedId == null) { pick(null) }
                    }
                    if (starred.isNotEmpty()) {
                        item("h-fav") { Section(stringResource(R.string.fonts_section_favourites)) }
                        items(starred, key = { "fav-" + it.id }) { row(it, "fav") }
                    }
                    if (recent.isNotEmpty() && query.isBlank()) {
                        item("h-recent") { Section(stringResource(R.string.fonts_section_recent)) }
                        items(recent, key = { "recent-" + it.id }) { row(it, "recent") }
                    }
                    item("h-fonts") { Section(stringResource(R.string.fonts_section_fonts)) }
                    items(installed, key = { it.id }) { row(it, "all") }
                    if (installed.isEmpty() && more.isEmpty()) item("none") {
                        Text(stringResource(R.string.fonts_none_found), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted, modifier = Modifier.padding(vertical = Spacing.S))
                    }
                    item("h-mine") { Section(stringResource(R.string.fonts_section_mine)) }
                    items(mine, key = { it.id }) { f ->
                        val ok = fits(f)
                        FontRow(
                            f.name, f.id, sample(f), f.id == selectedId,
                            note = if (!ok && script != null) stringResource(R.string.fonts_missing_script, scriptName(script)) else null,
                            enabled = ok,
                            onDelete = { scope.launch { registry.delete(f.id) } },
                        ) { pick(f.id) }
                    }
                    item("add") {
                        Column(Modifier.padding(vertical = Spacing.S)) {
                            OutlinedButton(onClick = { picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }) {
                                Text(stringResource(R.string.fonts_add_own))
                            }
                            Text(stringResource(R.string.fonts_add_hint), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                        }
                    }
                    if (more.isNotEmpty()) item("h-more") {
                        Section(stringResource(R.string.fonts_section_more))
                        Text(stringResource(R.string.fonts_more_hint), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted, modifier = Modifier.padding(bottom = Spacing.XS))
                    }
                    items(more, key = { it.id }) { c ->
                        val busy = c.id in downloading
                        val download = {
                            scope.launch {
                                registry.download(c).onSuccess { added = it.name; error = null; pick(it.id) }.onFailure { error = it }
                            }
                            Unit
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = download).padding(vertical = Spacing.XS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.family, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (listOf(categoryName(c.category)) + c.scripts.map { scriptName(it) }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BestNoteTheme.colors.muted,
                                )
                            }
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(Spacing.S))
                                Text(stringResource(R.string.fonts_downloading), style = MaterialTheme.typography.labelMedium)
                            } else {
                                TextButton(onClick = download) {
                                    Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.XS))
                                    Text(stringResource(R.string.fonts_download, "%.0f KB".format(c.bytes / 1024f)))
                                }
                            }
                        }
                    }
                }
                errorText(error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                added?.takeIf { error == null }?.let { Text(stringResource(R.string.fonts_added, it), color = BestNoteTheme.colors.success, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.fonts_close)) }
                }
            }
        }
    }
}

@Composable
private fun categoryName(c: FontCategory) = stringResource(
    when (c) {
        FontCategory.Sans -> R.string.fonts_cat_sans
        FontCategory.Serif -> R.string.fonts_cat_serif
        FontCategory.Mono -> R.string.fonts_cat_mono
        FontCategory.Handwriting -> R.string.fonts_cat_hand
        FontCategory.Display -> R.string.fonts_cat_display
    },
)

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = Spacing.S)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.S, bottom = Spacing.XS))
    }
}

@Composable
private fun FontRow(
    name: String,
    fontId: String?,
    sample: String,
    selected: Boolean,
    note: String? = null,
    enabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    favourite: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val family = rememberFontFamily(fontId)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = Spacing.S).semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(Spacing.S))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted)
            Text(
                sample,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = family ?: MaterialTheme.typography.titleMedium.fontFamily, fontSize = 22.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
            )
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (enabled) BestNoteTheme.colors.muted else BestNoteTheme.colors.warning) }
        }
        onFavourite?.let {
            IconButton(onClick = it) {
                Icon(
                    if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    stringResource(if (favourite) R.string.fonts_unfavourite else R.string.fonts_favourite, name),
                    tint = if (favourite) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
                )
            }
        }
        onDelete?.let {
            IconButton(onClick = it) { Icon(Icons.Rounded.Delete, stringResource(R.string.fonts_delete, name)) }
        }
    }
}

/** The licence of every downloaded OFL font (Settings → About → Licenses). */
@Composable
fun DownloadedFontLicenses(modifier: Modifier = Modifier, registry: FontRegistry = FontRegistry.get(LocalContext.current)) {
    val fonts by registry.fonts.collectAsState()
    val downloaded = fonts.filter { it.origin == FontOrigin.Downloaded }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(stringResource(R.string.fonts_licenses_title), style = MaterialTheme.typography.titleSmall)
        if (downloaded.isEmpty()) Text(stringResource(R.string.fonts_licenses_none), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
        downloaded.forEach { f ->
            var open by remember { mutableStateOf(false) }
            val c = FontCatalogue.byId(f.id)
            Column {
                Text(f.name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.fonts_license_ofl), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                c?.let { Text(stringResource(R.string.fonts_license_source, it.pageUrl), style = MaterialTheme.typography.labelSmall, color = BestNoteTheme.colors.muted) }
                TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.fonts_license_hide else R.string.fonts_license_show)) }
                if (open) Text(registry.licenseText(f.id) ?: c?.licenseUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
