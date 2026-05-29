package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegateSelectionTest {
    // A fake probe standing in for the real GPU/validation checks.
    private fun choose(gpuSupported: Boolean, gpuCreates: Boolean, validates: Boolean) =
        selectDelegatePath(
            gpuSupportedOnDevice = { gpuSupported },
            tryCreateGpu = { if (gpuCreates) Unit else throw RuntimeException("no gpu") },
            validate = { validates },
        )

    @Test fun gpu_when_supported_creates_and_validates() {
        assertEquals(DelegatePath.GPU, choose(true, true, true))
    }
    @Test fun cpu_when_unsupported() {
        assertEquals(DelegatePath.CPU, choose(false, true, true))
    }
    @Test fun cpu_when_create_throws() {
        assertEquals(DelegatePath.CPU, choose(true, false, true))
    }
    @Test fun cpu_when_validation_fails() {
        assertEquals(DelegatePath.CPU, choose(true, true, false))
    }
}
