package com.posterpdf.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singleton holding the user's current credit balance.
 *
 * Phase G3 (initial wiring): values are pushed in by:
 *   * [BillingRepository] after a successful purchase / test grant
 *   * `MainViewModel` on initial bootstrap (read from Firestore `users/{uid}.credits`)
 *   * A 30-second poll loop (placeholder; replaced in G12 by a real-time
 *     Firestore listener)
 *
 * Held outside the ViewModel so non-Compose callers (BillingRepository,
 * background workers) can update it without an explicit ViewModel reference.
 *
 * Thread-safe: writes go through the StateFlow's atomic `update`. Reads are
 * lock-free.
 */
object CreditBalance {

    private val _flow = MutableStateFlow(0)

    /** Hot, conflated stream of the current credit balance. Never replays stale values. */
    val flow: StateFlow<Int> = _flow.asStateFlow()

    /** Replace the balance with [value]. Use after authoritative server reads. */
    fun set(value: Int) {
        _flow.value = value.coerceAtLeast(0)
    }

    /**
     * Optimistically bump the balance by [delta] (can be negative for spend).
     * Server-authoritative refresh ([set]) should follow shortly.
     */
    fun increment(delta: Int) {
        _flow.update { (it + delta).coerceAtLeast(0) }
    }

    /** Atomic update helper — kept private so callers go through [set]/[increment]. */
    private inline fun MutableStateFlow<Int>.update(transform: (Int) -> Int) {
        while (true) {
            val current = value
            val next = transform(current)
            if (compareAndSet(current, next)) return
        }
    }
}
