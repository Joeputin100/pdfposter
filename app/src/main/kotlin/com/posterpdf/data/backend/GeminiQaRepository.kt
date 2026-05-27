package com.posterpdf.data.backend

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * RC65: Firebase-callable wrapper for the askGemini Cloud Function
 * (backend/functions/src/askGemini.ts). Mirrors AiUpscaleRepository's
 * pattern (FirebaseFunctions.getInstance + getHttpsCallable + await),
 * just for a different callable. Kept in its own file so the focused
 * surface stays under ~100 lines.
 */
class GeminiQaRepository {
    private val tag = "GeminiQaRepository"

    suspend fun askGemini(
        prompt: String,
        imageGsUri: String?,
        currentSettings: Map<String, Any?>,
    ): Result<AskGeminiResponse> = try {
        val functions = FirebaseFunctions.getInstance("us-central1")
        val payload = buildMap<String, Any?> {
            put("prompt", prompt)
            if (imageGsUri != null) put("imageGsUri", imageGsUri)
            if (currentSettings.isNotEmpty()) put("currentSettings", currentSettings)
        }
        val callableResult = functions
            .getHttpsCallable("askGemini")
            .call(payload)
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = callableResult.data as? Map<String, Any?>
            ?: return Result.failure(IllegalStateException("askGemini returned non-map"))
        val text = data["text"] as? String ?: ""
        val toolCallRaw = data["toolCall"] as? Map<String, Any?>
        val toolCall = toolCallRaw?.let {
            ToolCall(
                name = it["name"] as? String ?: return@let null,
                @Suppress("UNCHECKED_CAST")
                args = (it["args"] as? Map<String, Any?>) ?: emptyMap(),
            )
        }
        val remainingQueries = (data["remainingQueries"] as? Number)?.toInt() ?: 0
        Log.i(tag, "askGemini reply: ${text.length} chars, toolCall=${toolCall?.name}, remaining=$remainingQueries")
        Result.success(
            AskGeminiResponse(
                text = text,
                toolCall = toolCall,
                remainingQueries = remainingQueries,
            )
        )
    } catch (t: Throwable) {
        Log.w(tag, "askGemini failed: ${t.message}", t)
        Result.failure(t)
    }
}

/** Reply from the askGemini Cloud Function. [text] is the plain reply,
 *  [toolCall] is non-null when Gemini chose to invoke an agent function
 *  (Phase B0's PosterPdfAgentFunctions), and [remainingQueries] is the
 *  daily-quota counter to surface in the UI. */
data class AskGeminiResponse(
    val text: String,
    val toolCall: ToolCall?,
    val remainingQueries: Int,
)

/** A function-call request from Gemini. The Android client is responsible
 *  for routing it to the local agent function implementation. */
data class ToolCall(
    val name: String,
    val args: Map<String, Any?>,
)
