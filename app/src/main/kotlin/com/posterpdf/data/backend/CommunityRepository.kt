package com.posterpdf.data.backend

import android.util.Log
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * RC44 — community board Firestore I/O.
 *
 * Schema:
 *   /community/posts/{postId}                 — top-level posts
 *   /community/posts/{postId}/replies/{rId}   — flat 1-level replies
 *   /admins/{uid}                             — empty doc, presence == admin
 *
 * Reads are open to anyone (firestore.rules `allow read: if true`).
 * Writes go through firestore.rules `isRegistered()` (non-anonymous);
 * the Release-Notes topic adds `isAdmin()` on top of that. Errors here
 * are wrapped in Result<T> so the UI layer can surface them as toasts.
 */
object CommunityRepository {
    private const val TAG = "CommunityRepo"
    private val db get() = FirebaseFirestore.getInstance()

    object Topic {
        const val RELEASE_NOTES = "release_notes"
        const val FEATURE_REQUESTS = "feature_requests"
        const val TROUBLESHOOTING = "troubleshooting"
        const val DISCUSSION = "discussion"
        val ALL = listOf(RELEASE_NOTES, FEATURE_REQUESTS, TROUBLESHOOTING, DISCUSSION)
    }

    data class CommunityPost(
        val id: String,
        val uid: String,
        val authorName: String,
        val authorPhoto: String?,
        val topic: String,
        val title: String,
        val body: String,
        val createdAt: Date,
        val deletedAt: Date?,
    )

    data class CommunityReply(
        val id: String,
        val uid: String,
        val authorName: String,
        val authorPhoto: String?,
        val body: String,
        val createdAt: Date,
        val deletedAt: Date?,
    )

    /**
     * Fetch up to [limit] newest posts, optionally filtered by topic.
     * topic == null → "All" feed.
     */
    suspend fun fetchPosts(topic: String?, limit: Long = 50L): Result<List<CommunityPost>> = try {
        val base: Query = db.collection("community").document("posts").collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
        val q: Query = if (topic != null) base.whereEqualTo("topic", topic) else base
        val snap = q.get().await()
        val posts = snap.documents.mapNotNull { d ->
            CommunityPost(
                id = d.id,
                uid = d.getString("uid") ?: return@mapNotNull null,
                authorName = d.getString("authorName") ?: "",
                authorPhoto = d.getString("authorPhoto"),
                topic = d.getString("topic") ?: Topic.DISCUSSION,
                title = d.getString("title") ?: "",
                body = d.getString("body") ?: "",
                createdAt = d.getTimestamp("createdAt")?.toDate() ?: return@mapNotNull null,
                deletedAt = d.getTimestamp("deletedAt")?.toDate(),
            )
        }
        Result.success(posts)
    } catch (t: Throwable) {
        Log.w(TAG, "fetchPosts failed: ${t.message}")
        Result.failure(t)
    }

    suspend fun fetchPost(postId: String): Result<CommunityPost> = try {
        val d = db.collection("community").document("posts")
            .collection("items").document(postId).get().await()
        if (!d.exists()) return Result.failure(NoSuchElementException("post $postId not found"))
        Result.success(
            CommunityPost(
                id = d.id,
                uid = d.getString("uid") ?: "",
                authorName = d.getString("authorName") ?: "",
                authorPhoto = d.getString("authorPhoto"),
                topic = d.getString("topic") ?: Topic.DISCUSSION,
                title = d.getString("title") ?: "",
                body = d.getString("body") ?: "",
                createdAt = d.getTimestamp("createdAt")?.toDate() ?: Date(),
                deletedAt = d.getTimestamp("deletedAt")?.toDate(),
            ),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "fetchPost failed: ${t.message}")
        Result.failure(t)
    }

    suspend fun createPost(
        uid: String,
        authorName: String,
        authorPhoto: String?,
        topic: String,
        title: String,
        body: String,
    ): Result<String> = try {
        require(topic in Topic.ALL) { "invalid topic: $topic" }
        val payload = mutableMapOf<String, Any?>(
            "uid" to uid,
            "authorName" to authorName,
            "topic" to topic,
            "title" to title.trim(),
            "body" to body.trim(),
            "createdAt" to FieldValue.serverTimestamp(),
        )
        if (authorPhoto != null) payload["authorPhoto"] = authorPhoto
        val ref = db.collection("community").document("posts")
            .collection("items").add(payload).await()
        Log.i(TAG, "post created: ${ref.id}")
        Result.success(ref.id)
    } catch (t: Throwable) {
        Log.w(TAG, "createPost failed: ${t.message}")
        Result.failure(t)
    }

    suspend fun fetchReplies(postId: String, limit: Long = 200L): Result<List<CommunityReply>> = try {
        val snap = db.collection("community").document("posts")
            .collection("items").document(postId)
            .collection("replies")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(limit)
            .get().await()
        val replies = snap.documents.mapNotNull { d ->
            CommunityReply(
                id = d.id,
                uid = d.getString("uid") ?: return@mapNotNull null,
                authorName = d.getString("authorName") ?: "",
                authorPhoto = d.getString("authorPhoto"),
                body = d.getString("body") ?: "",
                createdAt = d.getTimestamp("createdAt")?.toDate() ?: return@mapNotNull null,
                deletedAt = d.getTimestamp("deletedAt")?.toDate(),
            )
        }
        Result.success(replies)
    } catch (t: Throwable) {
        Log.w(TAG, "fetchReplies failed: ${t.message}")
        Result.failure(t)
    }

    suspend fun createReply(
        postId: String,
        uid: String,
        authorName: String,
        authorPhoto: String?,
        body: String,
    ): Result<String> = try {
        val payload = mutableMapOf<String, Any?>(
            "uid" to uid,
            "authorName" to authorName,
            "body" to body.trim(),
            "createdAt" to FieldValue.serverTimestamp(),
        )
        if (authorPhoto != null) payload["authorPhoto"] = authorPhoto
        val ref = db.collection("community").document("posts")
            .collection("items").document(postId)
            .collection("replies").add(payload).await()
        Result.success(ref.id)
    } catch (t: Throwable) {
        Log.w(TAG, "createReply failed: ${t.message}")
        Result.failure(t)
    }

    suspend fun softDeletePost(postId: String, byUid: String): Result<Unit> = try {
        db.collection("community").document("posts")
            .collection("items").document(postId)
            .update(
                mapOf(
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to byUid,
                ),
            ).await()
        Result.success(Unit)
    } catch (t: Throwable) {
        Log.w(TAG, "softDeletePost failed: ${t.message}")
        Result.failure(t)
    }

    suspend fun softDeleteReply(postId: String, replyId: String, byUid: String): Result<Unit> = try {
        db.collection("community").document("posts")
            .collection("items").document(postId)
            .collection("replies").document(replyId)
            .update(
                mapOf(
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to byUid,
                ),
            ).await()
        Result.success(Unit)
    } catch (t: Throwable) {
        Log.w(TAG, "softDeleteReply failed: ${t.message}")
        Result.failure(t)
    }

    /**
     * Returns true iff the signed-in uid has a doc at /admins/{uid}.
     * Rules permit a user to read only their OWN admin-status doc, so
     * this is a self-probe — not enumeration. The composer uses it to
     * decide whether to surface the Release-Notes topic option. The
     * authoritative gate is enforced in firestore.rules; this is just a
     * UI hint, so any failure returns the safe default (false).
     */
    suspend fun isAdmin(uid: String): Boolean = try {
        db.collection("admins").document(uid).get().await().exists()
    } catch (_: Throwable) { false }

    /** Lightweight reply count for a post. One billable read per call. */
    suspend fun replyCount(postId: String): Int = try {
        val agg = db.collection("community").document("posts")
            .collection("items").document(postId)
            .collection("replies")
            .count().get(AggregateSource.SERVER).await()
        agg.count.toInt()
    } catch (_: Throwable) {
        0
    }
}
