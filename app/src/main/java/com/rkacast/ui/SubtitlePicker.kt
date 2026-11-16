package com.rkacast.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rkacast.cast.CastManager
import com.rkacast.cast.MediaResolver
import com.rkacast.data.Settings
import java.util.Locale

/** Human-readable name for the ISO 639-2 codes used by OpenSubtitles ("eng" -> "English"). */
fun languageName(code: String): String {
    if (code.isBlank()) return "Unknown"
    val loc = Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language }.getOrNull() == code.lowercase() }
        ?: Locale(code)
    return loc.displayLanguage.ifBlank { code }.replaceFirstChar { it.uppercase() }
}

/**
 * Lets the user choose subtitles from Stremio addons, a URL, or a file on the phone. If something is
 * already playing on the TV it is reloaded with the new subtitle at the same position; otherwise the
 * choice is remembered for the next cast.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlePickerSheet(app: AppState) {
    val ctx = app.context
    val playing by CastManager.state.collectAsState()
    val preferred by Settings.subtitleLang.collectAsState()
    var urlInput by remember { mutableStateOf("") }
    val options = app.subtitleOptions
    val languages = remember(options) {
        options.map { it.lang }.distinct().sortedWith(compareBy<String> { it != preferred }.thenBy { languageName(it) })
    }
    var lang by remember(languages) { mutableStateOf(languages.firstOrNull()) }

    fun choose(pick: SubtitlePick?) {
        app.showSubtitlePicker = false
        if (playing.media != null) {
            CastManager.changeSubtitle(pick?.let { MediaResolver.subtitleFor(ctx, it.source) })
            app.message(pick?.let { "Subtitles: ${it.label}" } ?: "Subtitles off")
        } else {
            app.nextSubtitle = pick
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            choose(SubtitlePick(uri.lastPathSegment?.substringAfterLast('/') ?: "Local file", uri.toString()))
        }
    }

    ModalBottomSheet(onDismissRequest = { app.showSubtitlePicker = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Subtitles", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { choose(null) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SubtitlesOff, null); Spacer(Modifier.width(12.dp)); Text("No subtitles")
            }
            HorizontalDivider()

            if (options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("From your Stremio addons", style = MaterialTheme.typography.titleSmall)
                LazyRowChips(languages, lang) { lang = it }
                LazyColumn(Modifier.height(220.dp)) {
                    val forLang = options.filter { it.lang == lang }
                    items(forLang.size) { i ->
                        val s = forLang[i]
                        val label = "${languageName(s.lang)} #${i + 1}"
                        Text(
                            label,
                            Modifier.fillMaxWidth().clickable { choose(SubtitlePick(label, s.url)) }.padding(vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                Text(
                    "Tip: install the OpenSubtitles addon in the Stremio tab to get subtitles automatically for movies and shows.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Pick .srt / .ass / .ssa / .vtt file from phone")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlInput, onValueChange = { urlInput = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Subtitle URL (.srt, .ass, .ssa or .vtt)") },
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { choose(SubtitlePick(urlInput.substringAfterLast('/').ifBlank { "URL" }, urlInput.trim())) },
                enabled = urlInput.startsWith("http"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use this URL") }
        }
    }
}

@Composable
private fun LazyRowChips(items: List<String>, selected: String?, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow {
        items(items) { code ->
            FilterChip(
                selected = code == selected,
                onClick = { onSelect(code) },
                label = { Text(languageName(code)) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}
