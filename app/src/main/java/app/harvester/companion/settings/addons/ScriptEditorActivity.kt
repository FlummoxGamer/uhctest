package app.harvester.companion.settings.addons

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UserscriptManager
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class ScriptEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val scriptName = intent.getStringExtra("name")
        if (scriptName == null) {
            finish()
            return
        }
        val initialScript = UserscriptManager.loadScript(this, scriptName)
        if (initialScript == null) {
            Toast.makeText(this, "Script not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            UHTheme {
                val context = LocalContext.current
                var originalBody by remember { mutableStateOf(initialScript.body) }
                var lineCount by remember { mutableIntStateOf(initialScript.body.count { it == '\n' } + 1) }
                var charCount by remember { mutableIntStateOf(initialScript.body.length) }
                var isDirty by remember { mutableStateOf(false) }
                var showDiscardDialog by remember { mutableStateOf(false) }
                var showFindBar by remember { mutableStateOf(false) }
                var showGotoDialog by remember { mutableStateOf(false) }
                var wordWrap by remember { mutableStateOf(false) }
                var findQuery by remember { mutableStateOf("") }
                var replaceQuery by remember { mutableStateOf("") }

                val editorRef = remember { mutableStateOf<EditText?>(null) }
                val gutterRef = remember { mutableStateOf<TextView?>(null) }
                val vScrollRef = remember { mutableStateOf<ScrollView?>(null) }

                fun updateGutter(text: CharSequence) {
                    val lines = text.count { it == '\n' } + 1
                    lineCount = lines
                    charCount = text.length
                    gutterRef.value?.let { tv ->
                        if (tv.lineCount != lines) {
                            val sb = StringBuilder()
                            for (i in 1..lines) {
                                sb.append(i)
                                if (i < lines) sb.append('\n')
                            }
                            tv.text = sb.toString()
                        }
                    }
                }

                fun findNext() {
                    val ed = editorRef.value ?: return
                    val q = findQuery
                    if (q.isEmpty()) return
                    val txt = ed.text.toString()
                    val startFrom = ed.selectionEnd.coerceIn(0, txt.length)
                    var idx = txt.indexOf(q, startFrom, ignoreCase = true)
                    if (idx < 0 && startFrom > 0) idx = txt.indexOf(q, 0, ignoreCase = true)
                    if (idx >= 0) {
                        ed.requestFocus()
                        ed.setSelection(idx, (idx + q.length).coerceAtMost(txt.length))
                        ed.layout?.let { layout ->
                            val line = layout.getLineForOffset(idx)
                            vScrollRef.value?.smoothScrollTo(0, (layout.getLineTop(line) - 120).coerceAtLeast(0))
                        }
                    } else {
                        Toast.makeText(context, "No matches", Toast.LENGTH_SHORT).show()
                    }
                }

                fun replaceOne() {
                    val ed = editorRef.value ?: return
                    val q = findQuery
                    if (q.isEmpty()) return
                    val s = ed.selectionStart.coerceAtLeast(0)
                    val e = ed.selectionEnd.coerceAtLeast(0)
                    if (e > s && ed.text.subSequence(s, e).toString().equals(q, ignoreCase = true)) {
                        ed.text.replace(s, e, replaceQuery)
                    }
                    findNext()
                }

                fun replaceAll() {
                    val ed = editorRef.value ?: return
                    val q = findQuery
                    if (q.isEmpty()) return
                    val replaced = ed.text.toString().replace(q, replaceQuery, ignoreCase = true)
                    ed.setText(replaced)
                    Toast.makeText(context, "Replaced all", Toast.LENGTH_SHORT).show()
                }

                fun jumpToLine(targetLine: Int) {
                    val ed = editorRef.value ?: return
                    val layout = ed.layout ?: return
                    val clamped = (targetLine - 1).coerceIn(0, (ed.lineCount - 1).coerceAtLeast(0))
                    val offset = layout.getLineStart(clamped)
                    ed.requestFocus()
                    ed.setSelection(offset)
                    vScrollRef.value?.smoothScrollTo(0, (layout.getLineTop(clamped) - 120).coerceAtLeast(0))
                }

                fun confirmExit() {
                    val cur = editorRef.value?.text?.toString() ?: originalBody
                    if (cur != originalBody) {
                        showDiscardDialog = true
                    } else {
                        finish()
                    }
                }

                BackHandler { confirmExit() }

                if (showDiscardDialog) {
                    AlertDialog(
                        onDismissRequest = { showDiscardDialog = false },
                        title = { Text("Discard unsaved changes?", color = MaterialTheme.colorScheme.onSurface) },
                        confirmButton = {
                            TextButton(onClick = { showDiscardDialog = false; finish() }) {
                                Text("Discard", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDiscardDialog = false }) {
                                Text("Keep editing", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                if (showGotoDialog) {
                    var lineInput by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showGotoDialog = false },
                        title = { Text("Go to Line (1–$lineCount)", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            OutlinedTextField(
                                value = lineInput,
                                onValueChange = { lineInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Line number") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val ln = lineInput.toIntOrNull()
                                showGotoDialog = false
                                if (ln != null) jumpToLine(ln)
                            }) {
                                Text("Go", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGotoDialog = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val gutterTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f).toArgb()
                val surfaceBgColor = MaterialTheme.colorScheme.surface.toArgb()
                val gutterBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f).toArgb()

                UhSettingsScaffold(
                    title = initialScript.displayName.ifBlank { initialScript.name },
                    subtitle = "$lineCount lines · ${charCount / 1024} KB${if (isDirty) " · modified" else ""}",
                    scrollable = false,
                    onBack = { confirmExit() },
                    actions = {
                        Row(modifier = Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    val reloaded = UserscriptManager.loadScript(context, scriptName)
                                    if (reloaded != null) {
                                        originalBody = reloaded.body
                                        editorRef.value?.setText(reloaded.body)
                                        isDirty = false
                                        Toast.makeText(context, "Reloaded from disk", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Reload", fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    val body = editorRef.value?.text?.toString().orEmpty()
                                    if (body.isBlank()) {
                                        Toast.makeText(context, "Empty script", Toast.LENGTH_SHORT).show()
                                    } else if (UserscriptManager.saveEdited(context, scriptName, body)) {
                                        originalBody = body
                                        isDirty = false
                                        GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                                        Toast.makeText(context, "Saved & Reloaded", Toast.LENGTH_SHORT).show()
                                        finish()
                                    } else {
                                        Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .imePadding()
                    ) {
                        // Compact utility toolbar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EditorToolbarChip("Find / Replace", selected = showFindBar) { showFindBar = !showFindBar }
                            EditorToolbarChip("Go to Line") { showGotoDialog = true }
                            EditorToolbarChip(if (wordWrap) "Wrap: ON" else "Wrap: OFF", selected = wordWrap) {
                                wordWrap = !wordWrap
                                editorRef.value?.setHorizontallyScrolling(!wordWrap)
                            }
                        }

                        if (showFindBar) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = findQuery,
                                        onValueChange = { findQuery = it },
                                        placeholder = { Text("Find…", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = replaceQuery,
                                        onValueChange = { replaceQuery = it },
                                        placeholder = { Text("Replace…", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EditorToolbarChip("Next") { findNext() }
                                    EditorToolbarChip("Replace") { replaceOne() }
                                    EditorToolbarChip("Replace All") { replaceAll() }
                                }
                            }
                        }

                        // Native lag-free scrollable EditText + synchronized line-number gutter
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    val vScroll = ScrollView(ctx).apply {
                                        isFillViewport = true
                                        setBackgroundColor(surfaceBgColor)
                                    }
                                    vScrollRef.value = vScroll

                                    val row = LinearLayout(ctx).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                        )
                                    }

                                    val gutter = TextView(ctx).apply {
                                        typeface = Typeface.MONOSPACE
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                                        setTextColor(gutterTextColor)
                                        setBackgroundColor(gutterBgColor)
                                        gravity = Gravity.END
                                        setPadding(16, 24, 14, 24)
                                        setLineSpacing(0f, 1.35f)
                                        layoutParams = LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                    gutterRef.value = gutter

                                    val hScroll = HorizontalScrollView(ctx).apply {
                                        isFillViewport = true
                                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    }

                                    val editText = EditText(ctx).apply {
                                        typeface = Typeface.MONOSPACE
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                                        setTextColor(textColor)
                                        background = null
                                        gravity = Gravity.TOP or Gravity.START
                                        setPadding(18, 24, 24, 24)
                                        setLineSpacing(0f, 1.35f)
                                        inputType = InputType.TYPE_CLASS_TEXT or
                                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                        setHorizontallyScrolling(true)
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                        )
                                        setText(initialScript.body)
                                    }
                                    editorRef.value = editText
                                    updateGutter(initialScript.body)

                                    editText.addTextChangedListener(object : TextWatcher {
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                        override fun afterTextChanged(s: Editable?) {
                                            val textStr = s?.toString().orEmpty()
                                            isDirty = textStr != originalBody
                                            updateGutter(textStr)
                                        }
                                    })

                                    hScroll.addView(editText)
                                    row.addView(gutter)
                                    row.addView(hScroll)
                                    vScroll.addView(row)
                                    vScroll
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorToolbarChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}
