package com.posterpdf.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.posterpdf.ui.components.preview.PRINTER_INK_AGSL
import com.posterpdf.ui.components.preview.drawPrinter

/**
 * API-isolation shim for the printer ink AGSL shader.
 *
 * `android.graphics.RuntimeShader` is API 33 (TIRAMISU). The instrumentation
 * Test Battery crashed on API 23/26/28/30 with
 * `java.lang.NoClassDefFoundError: android.graphics.RuntimeShader` →
 * "Process crashed", even though every *runtime* use was gated behind
 * `Build.VERSION.SDK_INT >= 33`.
 *
 * Why the runtime guard wasn't enough: ART verifies a method when its declaring
 * CLASS is first loaded. When `PosterPreviewKt` was loaded, the verifier had to
 * resolve every type named in `PosterPreview`'s bytecode — which included the
 * `RuntimeShader(...)` constructor and the `RuntimeShader?`-typed local passed
 * to `drawPrinter`. On a pre-33 device that class is absent, so verification of
 * `PosterPreview` failed and the whole process crashed before the
 * `cycleEnabled` (>= 33) `if` ever executed.
 *
 * The fix is classic API isolation: keep ALL `RuntimeShader` references in this
 * SEPARATE class. ART loads/verifies THIS class lazily — only the first time a
 * method here is actually invoked — and we only invoke it from the `>= 33`
 * path. `PosterPreview`'s own bytecode now names only `Any` and the functions
 * below (whose descriptors are `Object` / primitives), so loading
 * `PosterPreviewKt` no longer forces `RuntimeShader` to resolve. There are no
 * `android.graphics.RenderEffect` (API 31) references anywhere in PosterPreview
 * or its draw path, so no further isolation is needed for that class.
 *
 * The return type of [createPrinterInkShader] is deliberately `Any` (not
 * `RuntimeShader`) so the field that holds it in `PosterPreview` can be typed
 * `Any?` without the caller's bytecode naming `RuntimeShader`.
 */

/**
 * Construct the printer-ink [android.graphics.RuntimeShader]. MUST only be
 * called on API 33+ (the `@RequiresApi` enforces this at compile time, and the
 * caller additionally guards with `Build.VERSION.SDK_INT >= 33`). Returned as
 * `Any` so callers never name `RuntimeShader` in their own bytecode.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun createPrinterInkShader(): Any =
    android.graphics.RuntimeShader(PRINTER_INK_AGSL)

/**
 * Draw the printer, applying [inkShader] (an [android.graphics.RuntimeShader]
 * created by [createPrinterInkShader], or `null`) only on API 33+. Accepting
 * `Any?` keeps `RuntimeShader` out of the *caller's* bytecode; the cast and the
 * `drawPrinter(... : RuntimeShader?)` call live here, in a class that is only
 * loaded/verified when this function is first invoked (which only happens on
 * the API 33+ path). On older devices `drawPrinter` is invoked with `null` and
 * uses its flat-ink fallback.
 */
fun DrawScope.drawPrinterWithInk(
    cx: Float,
    topY: Float,
    width: Float,
    appearT: Float,
    inkScanT: Float,
    inkShader: Any?,
) {
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        inkShader as? android.graphics.RuntimeShader
    } else {
        null
    }
    drawPrinter(
        cx = cx,
        topY = topY,
        width = width,
        appearT = appearT,
        inkScanT = inkScanT,
        inkShader = shader,
    )
}
