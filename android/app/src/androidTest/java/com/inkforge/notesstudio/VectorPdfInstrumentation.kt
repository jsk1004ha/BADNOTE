package com.inkforge.notesstudio

import android.app.Instrumentation
import android.os.Bundle

/** Minimum-API PDF checks must not load the general runner's API 24+ PixelCopy lambdas. */
class VectorPdfInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".debug"))
            VectorPdfChecks.run(targetContext) { phase ->
                sendStatus(1, Bundle().apply { putString("vectorPdfPhase", phase) })
            }
            result.putString("passed", "true")
            result.putString("vectorPdf", "native_pages_brushes_fonts_background_budget_cancel_verified")
        } catch (error: Throwable) {
            result.putString("passed", "false"); result.putString("error", error.stackTraceToString())
        }
        finish(0, result)
    }
}
