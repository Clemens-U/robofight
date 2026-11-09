package robofight.fireline.app

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import robofight.fireline.app.editor.EditorModel
import robofight.fireline.app.files.FirmwareFile
import robofight.fireline.app.files.FirmwareRepository

/**
 * FILES surface (DESIGN-FIRELINE.md 5.1): `[ NEW FILE ]`, one card per stored
 * document with its byte count and instruction count (`42 B  18 i`), `EDIT`,
 * the `[>]` select-as-mission-firmware affordance, plus COPY / DEL, and
 * IMPORT / EXPORT along the bottom edge.
 *
 * Card numbers are computed on every refresh from the stored source and the
 * shared RF-8 assembler (`EditorModel.cardStats`), so a card never shows a
 * stale count. The MISSION tab consumes [selectedName]; it is persisted by
 * MainActivity so a rotation keeps the selection.
 */
class FilesSurface(
    private val activity: Activity,
    private val theme: FirelineTheme,
    private val store: FirmwareRepository,
    private val onNewFile: () -> Unit,
    private val onEdit: (FirmwareFile) -> Unit,
    private val onDuplicate: (FirmwareFile) -> Unit,
    private val onDelete: (FirmwareFile) -> Unit,
    private val onSelectFirmware: (FirmwareFile) -> Unit,
    private val onImport: () -> Unit,
    private val onExport: (FirmwareFile?) -> Unit,
) {
    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
    }
    private val notice = text(8, theme.textDim)
    private val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
    }

    val root: View = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        addView(column)
    }

    init {
        column.addView(button("NEW FILE", theme.runFill, theme.runText, onNewFile))
        // Status row between the button and the card list (milestone 8 line
        // spacing polish: the row used to sit flush against both).
        notice.layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = theme.chromeBlockGapPx()
        }
        column.addView(notice)
        column.addView(list)
        column.addView(importExportRow())
    }

    private var selectedName: String? = null

    /** Rebuild every card. [selectedName] is the firmware selected for MISSION. */
    fun refresh(selectedName: String?) {
        this.selectedName = selectedName
        list.removeAllViews()
        val files = store.list()
        notice.text = if (files.isEmpty()) {
            "no files yet  //  NEW FILE or IMPORT .asm"
        } else {
            "${files.size} file(s)  //  mission firmware: ${selectedName ?: "none selected"}"
        }
        for (file in files) list.addView(card(file, selectedName))
    }

    fun setNotice(message: String) {
        notice.text = message
    }

    private fun importExportRow(): View {
        val row = row(matchParent, wrapContent).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(10)
            }
        }
        row.addView(button("IMPORT .asm", theme.secondaryFill, theme.secondaryText, onImport))
        row.addView(spacer())
        row.addView(button("EXPORT .asm", theme.secondaryFill, theme.secondaryText, { onExport(currentSelection()) }))
        return row
    }

    /** EXPORT without an explicit pick exports the file selected for MISSION. */
    private fun currentSelection(): FirmwareFile? =
        selectedName?.let { store.find(it) } ?: store.list().firstOrNull()

    private fun card(file: FirmwareFile, selectedName: String?): View {
        val stats = EditorModel.cardStats(file.source)
        val card = column(wrapContent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        }
        val header = row(matchParent, wrapContent)
        header.addView(text(9, theme.textBright, file.name, theme.displayFace))
        header.addView(spacer())
        header.addView(text(8, theme.textDim, stats.format(), theme.workFace))
        card.addView(header)

        val meta = row(matchParent, wrapContent).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(6)
            }
        }
        meta.addView(text(7, theme.textDim, "edited " + formatFileTime(file.updatedMillis), theme.workFace))
        meta.addView(spacer())
        card.addView(meta)

        val actions = row(matchParent, wrapContent).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(6)
            }
        }
        actions.addView(button("EDIT", theme.secondaryFill, theme.secondaryText, { onEdit(file) }))
        val selected = file.name.equals(selectedName, ignoreCase = true)
        actions.addView(button("SELECT", theme.runFill, theme.runText, { onSelectFirmware(file) }).apply {
            isSelected = selected
            if (!selected) {
                background = theme.panelDrawable(fill = theme.panel, stroke = theme.border)
                setTextColor(theme.text)
            }
        })
        actions.addView(button("COPY", theme.secondaryFill, theme.secondaryText, { onDuplicate(file) }))
        actions.addView(button("DEL", theme.secondaryFill, theme.secondaryText, { onDelete(file) }))
        card.addView(actions)
        return card
    }

    // ---- view builders (house style) --------------------------------------------

    private fun button(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        text(8, textColor, label, theme.displayFace).apply {
            background = theme.panelDrawable(fill = fill, stroke = fill)
            setPadding(theme.dp(8), theme.dp(6), theme.dp(8), theme.dp(6))
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun text(sizeSp: Int, color: Int, value: String = "", face: android.graphics.Typeface = theme.displayFace): TextView =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
            setLineSpacing(theme.chromeLineSpacingPx().toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun row(width: Int, height: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun column(width: Int, height: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun spacer(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    companion object {
        private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
