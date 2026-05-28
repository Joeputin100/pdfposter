package com.posterpdf.agentfunctions

import com.posterpdf.ui.components.UpscaleModel
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterPdfAgentFunctionsTest {

    @Test fun `isHeadlessAllowed permits NONE in headless mode`() {
        val r = isHeadlessAllowed(model = UpscaleModel.NONE, headless = true, confirmCreditCost = false)
        assertTrue("expected Allowed, got $r", r is HeadlessRoutingResult.Allowed)
    }

    @Test fun `isHeadlessAllowed permits FREE_LOCAL in headless mode`() {
        val r = isHeadlessAllowed(model = UpscaleModel.FREE_LOCAL, headless = true, confirmCreditCost = false)
        assertTrue("expected Allowed, got $r", r is HeadlessRoutingResult.Allowed)
    }

    @Test fun `isHeadlessAllowed permits paid model only with explicit cost confirmation`() {
        val r = isHeadlessAllowed(model = UpscaleModel.TOPAZ, headless = true, confirmCreditCost = true)
        assertTrue("expected Allowed, got $r", r is HeadlessRoutingResult.Allowed)
    }

    @Test fun `isHeadlessAllowed rejects paid headless without confirmation`() {
        val r = isHeadlessAllowed(model = UpscaleModel.TOPAZ, headless = true, confirmCreditCost = false)
        assertTrue("expected RequiresConfirmation, got $r", r is HeadlessRoutingResult.RequiresConfirmation)
    }

    @Test fun `isHeadlessAllowed routes deep-link when not headless`() {
        val r = isHeadlessAllowed(model = UpscaleModel.TOPAZ, headless = false, confirmCreditCost = false)
        assertTrue("expected DeepLink, got $r", r is HeadlessRoutingResult.DeepLink)
    }

    @Test fun `isHeadlessAllowed deep-links free-tier when not headless`() {
        // Even a free model deep-links if !headless — the deep-link decision
        // comes first in the routing, not the free-tier check.
        val r = isHeadlessAllowed(model = UpscaleModel.FREE_LOCAL, headless = false, confirmCreditCost = false)
        assertTrue("expected DeepLink, got $r", r is HeadlessRoutingResult.DeepLink)
    }
}
