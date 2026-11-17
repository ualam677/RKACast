package com.rkacast.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rkacast.data.DebugLog

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun DebugLogDialog(app: AppState, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(DebugLog.dump()) }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Debug log", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Reproduce the problem, then Copy and paste this into the chat. Passwords, tokens and addon keys are hidden.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(DebugLog.dump()))
                        app.message("Log copied")
                    }) { Text("Copy") }
                    TextButton(onClick = { shareText(app.context, "RKACast debug log", DebugLog.dump()) }) { Text("Share") }
                    TextButton(onClick = { text = DebugLog.dump() }) { Text("Refresh") }
                    TextButton(onClick = { DebugLog.clear(); text = DebugLog.dump() }) { Text("Clear") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/** Shown on launch if the previous session crashed. */
@Composable
fun CrashReportDialog(app: AppState, report: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("RKACast crashed last time") },
        text = {
            Column {
                Text("Copy the crash report and paste it into the chat so it can be fixed.")
                Text(
                    report.lines().drop(2).take(6).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(AnnotatedString(report))
                app.message("Crash report copied")
                onDone()
            }) { Text("Copy report") }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = { shareText(app.context, "RKACast crash report", report); onDone() }) { Text("Share") }
                TextButton(onClick = onDone) { Text("Dismiss") }
            }
        },
    )
}
