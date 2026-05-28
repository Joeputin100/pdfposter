package com.posterpdf.upscale

import com.posterpdf.ui.components.UpscaleModel
import com.posterpdf.ui.components.UpscaleOption
import org.junit.Assert.assertEquals
import org.junit.Test

class PricingMathTest {
    private val noneOption = UpscaleOption(
        model = UpscaleModel.NONE,
        displayNameRes = 0, prosRes = 0, consRes = 0,
        scale = 1, supportedScales = listOf(1),
        perOutputMp = 0.0,
    )
    private val freeLocalOption = UpscaleOption(
        model = UpscaleModel.FREE_LOCAL,
        displayNameRes = 0, prosRes = 0, consRes = 0,
        scale = 4, supportedScales = listOf(4),
        perOutputMp = 0.0,
    )
    private val recraftOption = UpscaleOption(
        model = UpscaleModel.RECRAFT,
        displayNameRes = 0, prosRes = 0, consRes = 0,
        scale = 4, supportedScales = listOf(4),
        perOutputMp = 0.0, flatUsd = 0.004,
    )
    private val topazOption = UpscaleOption(
        model = UpscaleModel.TOPAZ,
        displayNameRes = 0, prosRes = 0, consRes = 0,
        scale = 4, supportedScales = listOf(2, 4, 6, 8),
        perOutputMp = 0.01,
    )

    @Test fun `pickScale picks smallest scale meeting target DPI`() {
        // 1 MP source, 24x18 poster at 150 DPI → target = 24*150*18*150/1e6 = 9.72 MP.
        // Topaz scales = [2,4,6,8]: scale 2 → 4 MP (misses), scale 4 → 16 MP (meets).
        val picked = pickScale(topazOption, inputMp = 1.0, posterWInches = 24.0, posterHInches = 18.0, targetDpi = 150)
        assertEquals(4, picked)
    }

    @Test fun `pickScale returns the only supported scale for single-scale models`() {
        val picked = pickScale(recraftOption, inputMp = 1.0, posterWInches = 24.0, posterHInches = 18.0, targetDpi = 150)
        assertEquals(4, picked)
    }

    @Test fun `creditsForOption returns 0 for NONE`() {
        assertEquals(0, creditsForOption(noneOption, inputMp = 1.0, pickedScale = 1))
    }

    @Test fun `creditsForOption returns 0 for FREE_LOCAL`() {
        assertEquals(0, creditsForOption(freeLocalOption, inputMp = 1.0, pickedScale = 4))
    }

    @Test fun `creditsForOption flat USD model returns flat fee converted to credits`() {
        // Recraft flatUsd=0.004 USD; ceil(0.004 / 0.00425) = ceil(0.941) = 1
        val credits = creditsForOption(recraftOption, inputMp = 1.0, pickedScale = 4)
        assertEquals(1, credits)
    }

    @Test fun `creditsForOption per-MP model scales with output area`() {
        // Topaz perOutputMp = 0.01 USD/MP. inputMp=1, scale=4 → outputMp=16 → cogs=0.16 USD
        // credits = ceil(0.16 / 0.00425) = ceil(37.647) = 38
        val credits = creditsForOption(topazOption, inputMp = 1.0, pickedScale = 4)
        assertEquals(38, credits)
    }

    @Test fun `creditsForOption coerces paid model min to 1 credit`() {
        // Topaz inputMp=0.0001, scale=2 → outputMp=0.0004 → cogs=0.000004 USD
        // ceil(0.000004 / 0.00425) = ceil(0.00094) = 1 via coerceAtLeast(1)
        val credits = creditsForOption(topazOption, inputMp = 0.0001, pickedScale = 2)
        assertEquals(1, credits)
    }
}
