package com.inkforge.notesstudio

/** Must execute on Android: desktop java.util.regex accepts brace syntax rejected by ICU. */
internal object MathEngineAndroidChecks {
    fun run() {
        val cases = listOf(
            "1+1" to 2.0,
            "\\frac{3}{2}" to 1.5,
            "\\frac{1}{\\frac{2}{3}}" to 1.5,
            "\\sqrt{9}" to 3.0,
            "\\sqrt{\\frac{9}{1}}" to 3.0,
            "2^{3}" to 8.0,
            // These are the actual supported OCR structural output forms consumed by the UI.
            "((1)/(2))" to .5,
            "(2)^(3)" to 8.0,
            "((1)/(2))+(2)^(3)" to 8.5)
        cases.forEach { (expression, expected) ->
            val actual = MathEngine().calculate(expression).value
            check(kotlin.math.abs(actual - expected) <= 1e-12) {
                "androidMathConsumerMismatch: $expression expected=$expected actual=$actual"
            }
        }
        check(runCatching { MathEngine().calculate("I+I") }.isFailure) {
            "Ambiguous recognized letters must not be silently substituted with digits"
        }
    }
}
