package com.posterpdf.data.backend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

/**
 * RC19 — Wires the AI upscale flow end-to-end on the client.
 *
 * Flow (called from MainViewModel.runAiUpscale):
 *   1. Read source bitmap from `sourceUri`, compute SHA-256, upload to
 *      Firebase Storage at `users/{uid}/upscale-input/{hash}.png`.
 *   2. Call the `requestUpscale` callable with modelId, the gs:// input URL,
 *      input megapixels, poster dimensions, target DPI. Backend stages
 *      credits + signs the gs:// URL + submits to FAL + polls + stores the
 *      result back in GCS.
 *   3. If callable returns synchronously with the upscale done, fetch the
 *      result URL from /upscaleTransactions/{txId}.outputUrl. If callable
 *      returned with status='in_progress', poll getUpscaleStatus until
 *      'succeeded' or 'failed'.
 *   4. Download the result image from the storage URL into local cache.
 *      Return the local File for the caller to swap in as selectedImageUri.
 *
 * Phase reporting: callers pass an [onPhase] callback so the UI can surface
 * UPLOADING / IN_QUEUE / IN_PROGRESS / DOWNLOADING / SAVING progress states
 * matching what the user asked for in RC15:
 *   "for AI models, progress card should show uploading, in_queue,
 *    in_progress, completed/failed processing, downloading as separate steps."
 *
 * Failure: the backend refunds credits internally on FAL error. Client-side
 * surface is just "Result.failure(t)"; UI shows errorMessage.
 */
class AiUpscaleRepository(private val auth: AuthRepository) {

    enum class Phase {
        UPLOADING, IN_QUEUE, IN_PROGRESS, DOWNLOADING, SAVING, SUCCEEDED, FAILED,
    }

    suspend fun runUpscale(
        context: Context,
        sourceUri: Uri,
        modelId: String,
        inputMp: Double,
        posterWidthInches: Double,
        posterHeightInches: Double,
        targetDpi: Int,
        // RC21: third arg `detail` carries human-readable progress detail
        // ("Queue position 3", "Inference 2.4 s", etc.) when available.
        // Null means "no detail to show this iteration." The MainViewModel
        // captures this into aiUpscaleDetail and the modal renders it
        // below the phase label.
        onPhase: (Phase, Float, String?) -> Unit,
    ): Result<File> {
        return try {
            auth.ensureSignedIn()
            val uid = auth.session.value.uid
                ?: return Result.failure(IllegalStateException("not signed in"))

            // 1. Read + hash the source bitmap.
            onPhase(Phase.UPLOADING, 0.05f, null)
            val srcBytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            } ?: return Result.failure(IllegalStateException("could not read source image"))

            val hash = sha256Hex(srcBytes).take(40)
            val storagePath = "users/$uid/upscale-input/$hash.png"

            // 2. Re-encode as PNG so the FAL pipeline gets lossless input
            //    (per project memory: lossless-only output formats; same applies
            //    to upload). If the source is already a PNG we still pay one
            //    decode-encode pass but keep the path uniform.
            val pngBytes = withContext(Dispatchers.IO) {
                val bmp = BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size)
                    ?: throw IllegalStateException("could not decode source bitmap")
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            }

            onPhase(Phase.UPLOADING, 0.15f, null)
            val storageRef = FirebaseStorage.getInstance().reference.child(storagePath)
            val uploadTask = storageRef.putBytes(pngBytes)
            uploadTask.addOnProgressListener { snap ->
                if (snap.totalByteCount > 0) {
                    val frac = snap.bytesTransferred.toFloat() / snap.totalByteCount
                    // Map upload progress to [0.15, 0.45] of the overall bar.
                    onPhase(Phase.UPLOADING, 0.15f + 0.30f * frac, null)
                }
            }
            uploadTask.await()
            // RC20: storageRef.path includes a leading slash, which produces
            // gs://bucket//users/... with a doubled slash. The backend's
            // resolveFetchableUrl looks up the literal path and returns
            // "Object does not exist at location" because GCS treats the
            // doubled slash as a real character. Use the local storagePath
            // (no leading slash) instead.
            val gsUri = "gs://${storageRef.bucket}/$storagePath"
            Log.i(TAG, "uploaded source: $gsUri")

            // 3. Call requestUpscale.
            onPhase(Phase.IN_QUEUE, 0.50f, null)
            val functions = FirebaseFunctions.getInstance("us-central1")
            val payload = mapOf(
                "modelId" to modelId,
                "inputUrl" to gsUri,
                "inputMp" to inputMp,
                "posterWidthInches" to posterWidthInches,
                "posterHeightInches" to posterHeightInches,
                "targetDpi" to targetDpi,
            )
            val callableResult = functions
                .getHttpsCallable("requestUpscale")
                .call(payload)
                .await()
            @Suppress("UNCHECKED_CAST")
            val resultMap = callableResult.data as? Map<String, Any?>
                ?: return Result.failure(IllegalStateException("requestUpscale returned non-map"))
            val txId = resultMap["txId"] as? String
                ?: return Result.failure(IllegalStateException("requestUpscale returned no txId"))
            Log.i(TAG, "upscale tx created: $txId")

            // 4. Poll for status. The callable usually completes within its
            //    own timeout (sets status='succeeded'), but if the FAL job
            //    took too long it'll return early with status='in_progress'.
            onPhase(Phase.IN_PROGRESS, 0.55f, null)
            val outputUrl = pollForCompletion(functions, txId, onPhase)

            // 5. Download.
            onPhase(Phase.DOWNLOADING, 0.85f, null)
            val outputBytes = withContext(Dispatchers.IO) {
                URL(outputUrl).openStream().use { it.readBytes() }
            }

            // 6. Save to local cache, mirroring the on-device upscale flow's
            //    output naming scheme so downstream code (PosterPreview etc.)
            //    treats it the same way.
            onPhase(Phase.SAVING, 0.95f, null)
            val outFile = File(
                context.cacheDir,
                "ai_upscaled_${System.currentTimeMillis()}.png",
            )
            withContext(Dispatchers.IO) {
                FileOutputStream(outFile).use { it.write(outputBytes) }
            }

            onPhase(Phase.SUCCEEDED, 1.0f, null)
            Result.success(outFile)
        } catch (t: Throwable) {
            Log.w(TAG, "ai upscale failed: ${t.message}", t)
            onPhase(Phase.FAILED, 0f, null)
            Result.failure(t)
        }
    }

    /** Poll /upscaleTransactions/{txId} via the getUpscaleStatus callable
     *  until status is 'succeeded' or 'failed'. Returns the outputUrl on
     *  success, throws on failure / timeout. */
    private suspend fun pollForCompletion(
        functions: FirebaseFunctions,
        txId: String,
        onPhase: (Phase, Float, String?) -> Unit,
    ): String {
        val started = System.currentTimeMillis()
        val deadline = started + 5 * 60 * 1000L
        var lastStatus: String? = null
        var lastDetail: String? = null
        while (System.currentTimeMillis() < deadline) {
            val res = functions
                .getHttpsCallable("getUpscaleStatus")
                .call(mapOf("txId" to txId))
                .await()
            @Suppress("UNCHECKED_CAST")
            val data = res.data as? Map<String, Any?> ?: emptyMap()
            val status = data["status"] as? String
            // RC21: queuePosition is persisted by the backend's pollFalJob each
            // iteration while the FAL job is IN_QUEUE / IN_PROGRESS. Surface
            // it as a human-readable detail string for the modal.
            val queuePos = (data["queuePosition"] as? Number)?.toInt()
            val detail = when {
                status == "in_queue" && queuePos != null && queuePos > 0 ->
                    "Queue position $queuePos"
                status == "in_queue" && queuePos == 0 -> "Almost up — about to start"
                status == "in_progress" -> "Processing image"
                else -> null
            }
            if (status != lastStatus) {
                Log.i(TAG, "tx $txId status=$status queue=$queuePos")
                lastStatus = status
                when (status) {
                    "in_queue" -> onPhase(Phase.IN_QUEUE, 0.55f, detail)
                    "in_progress" -> onPhase(Phase.IN_PROGRESS, 0.65f, detail)
                    else -> { /* stay on whatever phase the caller set */ }
                }
            } else if (detail != lastDetail) {
                // Same status, fresher queue position. Re-emit so the UI
                // text updates without bumping the progress bar fraction.
                val phase = when (status) {
                    "in_queue" -> Phase.IN_QUEUE
                    "in_progress" -> Phase.IN_PROGRESS
                    else -> null
                }
                if (phase != null) onPhase(phase, if (phase == Phase.IN_QUEUE) 0.55f else 0.65f, detail)
            }
            lastDetail = detail
            when (status) {
                "succeeded" -> {
                    val outputUrl = data["outputUrl"] as? String
                        ?: throw IllegalStateException("succeeded but no outputUrl")
                    return outputUrl
                }
                "failed" -> {
                    val msg = (data["failureReason"] as? String)
                        ?: (data["error"] as? String)
                        ?: "unknown"
                    throw IllegalStateException("upscale failed: $msg")
                }
                else -> delay(2000)
            }
        }
        throw IllegalStateException("upscale timed out after 5 minutes")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "AiUpscaleRepo"
    }
}
