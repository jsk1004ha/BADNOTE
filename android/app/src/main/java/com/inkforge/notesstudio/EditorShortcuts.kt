package com.inkforge.notesstudio

/** Platform-independent routing also protects Compose and native text fields. */
internal object EditorShortcuts {
    enum class Action { UNDO, REDO, SAVE, PEN, HIGHLIGHTER, ERASER, LASSO, HAND, TEXT, SHAPE, TAPE, LASER }
    fun resolve(key: String, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean,
                editingText: Boolean, editorOpen: Boolean, blocked: Boolean): Action? {
        if (!editorOpen || blocked || editingText || alt || meta) return null
        if (ctrl) return when (key) {
            "Z" -> if (shift) Action.REDO else Action.UNDO
            "Y" -> if (!shift) Action.REDO else null
            "S" -> if (!shift) Action.SAVE else null
            else -> null
        }
        if (shift) return null
        return when (key) {
            "1" -> Action.PEN; "2" -> Action.HIGHLIGHTER; "3" -> Action.ERASER
            "4" -> Action.LASSO; "5" -> Action.HAND; "6" -> Action.TEXT
            "7" -> Action.SHAPE; "8" -> Action.TAPE; "9" -> Action.LASER
            else -> null
        }
    }
}
