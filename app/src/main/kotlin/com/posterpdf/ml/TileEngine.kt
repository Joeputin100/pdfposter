package com.posterpdf.ml

enum class DelegatePath { GPU, CPU }

/**
 * Pure GPU-vs-CPU decision. GPU only if the device reports support AND the
 * delegate creates AND a validation inference passes; any failure → CPU.
 * Keeping this free of Android/native types makes it unit-testable on the JVM.
 */
internal fun selectDelegatePath(
    gpuSupportedOnDevice: () -> Boolean,
    tryCreateGpu: () -> Unit,
    validate: () -> Boolean,
): DelegatePath {
    if (!gpuSupportedOnDevice()) return DelegatePath.CPU
    return try {
        tryCreateGpu()
        if (validate()) DelegatePath.GPU else DelegatePath.CPU
    } catch (_: Throwable) {
        DelegatePath.CPU
    }
}
