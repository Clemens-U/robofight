package robofight.fireline.app

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import robofight.fireline.app.editor.EditorModel
import robofight.fireline.app.editor.SourceHighlight
import robofight.fireline.app.editor.TokenClass
import robofight.fireline.app.files.FirmwareFile
import robofight.fireline.app.files.FirmwareFileNames
import robofight.fireline.app.files.FirmwareSource
import robofight.fireline.app.files.FirmwareRepository
import robofight.fireline.app.files.SaveOutcome
import robofight.fireline.R

/**
 * Editor surface (DESIGN-FIRELINE.md 5.1/6): a full-screen surface of FILES,
 * not a fourth tab. 4-digit zero-padded gutter in text-dim, JetBrains Mono body
 * at 13sp with 1.5 line height, the four mnemonic/port/number/comment color
 * classes, and an assemble readout whose error rows jump to the reported line.
 *
 * The PC highlight from 6 belongs to the run surfaces (milestone 5) and is not
 * here yet. Saving is always through the repository, so every save passes the
 * same name/source validation as import.
 */
class EditorSurface(
    private val activity: Activity,
    private val theme: FirelineTheme,
    private val store: FirmwareRepository,
    file: FirmwareFile?,
    private val displayName: String,
    private val onClose: () -> Unit,
    private val onSaved: (FirmwareFile) -> Unit,
    private val onExport: (name: String, source: String) -> Unit,
    private val onRename: (FirmwareFile) -> Unit,
) {
    /** null until a new document is saved for the first time. */
    private var editing: FirmwareFile? = file

    /** Re-entrancy guard for the highlight pass (it calls EditText.setText). */
    private var highlighting = false

    private val bodyColumn = column(matchParent, matchParent)
    private val scroller = ScrollView(activity)
    private val gutter = TextView(activity)
    private val editor = EditText(activity)
    private val titleLine = text(9, theme.textBright, theme.displayFace)
    private val statusLine = text(8, theme.textDim, theme.workFace)
    private val errorPanel = column(matchParent, wrapContent)

    init {
        val head = column(matchParent, wrapContent).apply { setPadding(0, theme.dp(4), 0, theme.dp(4)) }
        head.addView(titleLine)
        head.addView(statusLine)
        head.addView(editorActions())
        head.addView(errorPanel)

        gutter.apply {
            setTextColor(theme.textDim)
            typeface = theme.workFace
            setTextSize(TypedValue.COMPLEX_UNIT_SP, GUTTER_SP)
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.END
            setPadding(0, theme.dp(6), theme.dp(6), 0)
        }
        editor.apply {
            setTextColor(theme.text)
            setBackgroundColor(theme.panel2)
            typeface = theme.workFace
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
            includeFontPadding = false
            setLineSpacing(0f, LINE_SPACING)
            // Lines must not wrap: a wrapped line would desync gutter numbers.
            setHorizontallyScrolling(true)
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            minLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(theme.dp(6), theme.dp(6), theme.dp(6), theme.dp(6))
            setHint(activity.getString(R.string.editor_hint))
            setHintTextColor(theme.textDim)
            setText(editing?.source ?: "")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // applyHighlight() re-sets the text; without this guard the
                    // watcher would re-trigger itself on every keystroke.
                    if (highlighting) return
                    refreshChrome()
                    applyHighlight()
                }
            })
        }

        val codeRow = row(matchParent, wrapContent).apply {
            gravity = Gravity.TOP
        }
        codeRow.addView(gutter, LinearLayout.LayoutParams(wrapContent, wrapContent))
        codeRow.addView(editor, LinearLayout.LayoutParams(0, wrapContent, 1f))
        scroller.addView(codeRow)

        bodyColumn.addView(head)
        bodyColumn.addView(scroller, LinearLayout.LayoutParams(matchParent, 0, 1f))
        syncLineMetrics()
        setDisplayName()
        refreshChrome()
        applyHighlight()
    }

    val root: View get() = bodyColumn

    /** After a rename the surface keeps editing the same row under a new name. */
    fun setEditing(file: FirmwareFile) {
        editing = file
        setDisplayName()
        refreshChrome()
    }

    /** The name this surface saves under (new documents land on [UNTITLED]). */
    private fun canonicalName(): String =
        FirmwareFileNames.sanitize(editing?.name ?: displayName) ?: UNTITLED

    private fun setDisplayName() {
        titleLine.text = "EDIT  " + canonicalName()
    }

    private fun editorActions(): View {
        val row = row(matchParent, wrapContent)
        row.addView(button("BACK", onClose))
        row.addView(button("SAVE", { save() }))
        row.addView(button("INSERT PORTS", { insertPorts() }))
        row.addView(button("EXPORT .asm", { onExport(currentName(), source()) }))
        row.addView(button("RENAME", { editing?.let(onRename) }))
        return row
    }

    // ---- chrome -----------------------------------------------------------------

    private fun source(): String = editor.text.toString()
    private fun currentName(): String = editing?.name ?: canonicalName()

    private fun refreshChrome() {
        val source = source()
        val lines = FirmwareSource.lineCount(source)
        gutter.text = EditorModel.gutterLabels(lines).joinToString("\n")
        val summary = EditorModel.assembleSource(source)
        statusLine.text = if (summary.ok) {
            "assembles  //  ${summary.instructionCount} instructions, ${summary.codeBytes} B"
        } else {
            "${summary.errors.size} error(s)  //  not runnable"
        }
        statusLine.setTextColor(if (summary.ok) theme.caret else theme.textBright)
        errorPanel.removeAllViews()
        for ((line, message) in summary.errors) {
            errorPanel.addView(errorRow(line, message))
        }
    }

    private fun errorRow(line: Int, message: String): TextView =
        text(8, theme.textBright, theme.workFace).apply {
            text = "line %04d  %s".format(line, message)
            setPadding(0, theme.dp(2), 0, theme.dp(2))
            setOnClickListener { jumpToLine(line) }
            contentDescription = "error on line $line, tap to jump"
        }

    /** DESIGN-FIRELINE 6: mnemonics, FIRELINE ports, literals, comments. */
    private fun applyHighlight() {
        highlighting = true
        val builder = android.text.SpannableStringBuilder(editor.text)
        for ((index, line) in editor.text.toString().split('\n').withIndex()) {
            val lineStart = EditorModel.lineStartOffset(editor.text.toString(), index + 1)
            for (token in SourceHighlight.tokens(line)) {
                val color = when (token.klass) {
                    TokenClass.MNEMONIC -> theme.text
                    TokenClass.PORT -> theme.exit
                    TokenClass.NUMBER -> theme.textBright
                    TokenClass.COMMENT -> theme.textDim
                    TokenClass.LABEL -> theme.rover
                    TokenClass.TEXT -> theme.text
                }
                builder.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    lineStart + token.start,
                    lineStart + token.end,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        val caret = editor.selectionStart.coerceIn(0, builder.length)
        val caretEnd = editor.selectionEnd.coerceIn(0, builder.length)
        editor.setText(builder)
        editor.setSelection(caret, caretEnd)
        highlighting = false
    }

    /** Gutter rows are padded so each 11sp number sits level with its 13sp line. */
    private fun syncLineMetrics() {
        editor.post {
            val bodyLine = editor.lineHeight
            val gutterLine = gutter.lineHeight
            if (bodyLine > gutterLine && gutterLine > 0) {
                gutter.setLineSpacing((bodyLine - gutterLine).toFloat(), 1f)
            }
        }
    }

    // ---- actions ----------------------------------------------------------------

    private fun save() {
        val source = source()
        val existing = editing
        val outcome = if (existing == null) store.save(canonicalName(), source) else store.update(existing.id, source)
        when (outcome) {
            is SaveOutcome.Saved -> {
                editing = outcome.file
                setDisplayName()
                val note = outcome.requestedName?.let { "saved as ${outcome.file.name}  //  $it was taken" }
                    ?: "saved ${outcome.file.name}"
                statusLine.text = note
                onSaved(outcome.file)
            }
            is SaveOutcome.Rejected -> {
                statusLine.setTextColor(theme.textBright)
                statusLine.text = outcome.problems.joinToString("  //  ")
            }
        }
    }

    private fun insertPorts() {
        val (updated, caret) = EditorModel.insertCheatSheet(source(), editor.selectionStart.coerceIn(0, source().length))
        if (updated == source()) {
            statusLine.text = "port cheat sheet already in this file"
            return
        }
        editor.setText(updated)
        editor.setSelection(caret.coerceIn(0, updated.length))
        refreshChrome()
        applyHighlight()
    }

    private fun jumpToLine(lineOneBased: Int) {
        val offset = EditorModel.lineStartOffset(source(), lineOneBased)
        editor.requestFocus()
        editor.setSelection(offset)
        val layout = editor.layout ?: return
        val visualLine = source().substring(0, offset).count { it == '\n' }
        scroller.smoothScrollTo(0, layout.getLineTop(visualLine))
    }

    // ---- view builders (house style) --------------------------------------------

    private fun button(label: String, onClick: () -> Unit): TextView =
        text(8, theme.secondaryText, theme.displayFace).apply {
            this.text = label
            background = theme.panelDrawable(fill = theme.secondaryFill, stroke = theme.border)
            setPadding(theme.dp(8), theme.dp(6), theme.dp(8), theme.dp(6))
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun text(sizeSp: Int, color: Int, face: Typeface): TextView =
        TextView(activity).apply {
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
        }

    private fun row(width: Int, height: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun column(width: Int, height: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    companion object {
        /** DESIGN-FIRELINE 3: editor body 13sp minimum, line height 1.5, gutter 11sp dim. */
        const val BODY_SP = 13f

        /** Fallback save name when a draft name has nothing legal in it. */
        const val UNTITLED = "UNTITLED.ASM"
        const val GUTTER_SP = 11f
        const val LINE_SPACING = 1.5f

        private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
