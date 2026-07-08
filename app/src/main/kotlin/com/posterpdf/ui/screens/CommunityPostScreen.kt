package com.posterpdf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.posterpdf.MainViewModel
import com.posterpdf.R
import com.posterpdf.data.backend.CommunityRepository
import com.posterpdf.data.backend.CommunityRepository.CommunityPost
import com.posterpdf.data.backend.CommunityRepository.CommunityReply
import com.posterpdf.ui.components.BBCodeText
import kotlinx.coroutines.launch

/**
 * RC44 — single-post view with replies and a reply input pinned to
 * the bottom (registered users only). The post body and replies are
 * rendered with the BBCode renderer so [b][i][url][img] all light up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPostScreen(
    viewModel: MainViewModel,
    postId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = viewModel.authSession
    val isRegistered = !session.isAnonymous && session.signedIn

    var post by remember { mutableStateOf<CommunityPost?>(null) }
    var replies by remember { mutableStateOf<List<CommunityReply>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var deletePostConfirm by remember { mutableStateOf(false) }
    var deleteReplyConfirm by remember { mutableStateOf<String?>(null) }

    // rc84: Play UGC policy — report + block. Replies from locally-blocked
    // authors are filtered out; the overflow menu on the post and each
    // reply feeds these two dialog targets.
    val blockedIds = viewModel.blockedUsers.keys
    var reportTarget by remember { mutableStateOf<ModerationTarget?>(null) }
    var blockTarget by remember { mutableStateOf<ModerationTarget?>(null) }

    LaunchedEffect(postId, refreshTick) {
        loading = true
        loadFailed = false
        val postResult = CommunityRepository.fetchPost(postId)
        val replyResult = CommunityRepository.fetchReplies(postId)
        post = postResult.getOrNull()
        replies = replyResult.getOrDefault(emptyList())
        loadFailed = postResult.isFailure
        loading = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.community_post_screen_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.help_back_cd))
                    }
                },
                actions = {
                    val current = post
                    if (current != null && current.deletedAt == null && current.uid == session.uid) {
                        IconButton(onClick = { deletePostConfirm = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.community_delete_post_action))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                loadFailed || post == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.community_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    val current = post!!
                    // rc84: filter replies from locally-blocked authors
                    // (Play UGC policy); the count label uses the filtered
                    // list so it matches what's on screen.
                    val visibleReplies = replies.filter { it.uid !in blockedIds }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            OriginalPost(
                                post = current,
                                // rc84: report/block only on OTHER people's
                                // live posts — hidden on own + deleted.
                                moderationTarget = if (
                                    session.uid != null && current.uid != session.uid &&
                                    current.uid.isNotBlank() && current.deletedAt == null
                                ) {
                                    ModerationTarget(
                                        targetType = CommunityRepository.ReportType.POST,
                                        postId = current.id,
                                        replyId = null,
                                        targetUid = current.uid,
                                        authorName = current.authorName,
                                    )
                                } else {
                                    null
                                },
                                onReport = { reportTarget = it },
                                onBlock = { blockTarget = it },
                            )
                        }
                        item {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        item {
                            Text(
                                text = when (visibleReplies.size) {
                                    0 -> stringResource(R.string.community_reply_count_zero)
                                    1 -> stringResource(R.string.community_reply_count_one)
                                    else -> stringResource(R.string.community_reply_count, visibleReplies.size)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        items(visibleReplies, key = { it.id }) { reply ->
                            ReplyCard(
                                reply = reply,
                                isOwner = reply.uid == session.uid,
                                onDelete = { deleteReplyConfirm = reply.id },
                                // rc84: report/block on others' live replies.
                                moderationTarget = if (
                                    session.uid != null && reply.uid != session.uid &&
                                    reply.uid.isNotBlank() && reply.deletedAt == null
                                ) {
                                    ModerationTarget(
                                        targetType = CommunityRepository.ReportType.REPLY,
                                        postId = postId,
                                        replyId = reply.id,
                                        targetUid = reply.uid,
                                        authorName = reply.authorName,
                                    )
                                } else {
                                    null
                                },
                                onReport = { reportTarget = it },
                                onBlock = { blockTarget = it },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    if (isRegistered) {
                        Surface(
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .imePadding()
                                .navigationBarsPadding(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = replyText,
                                    onValueChange = { replyText = it.take(4000) },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(stringResource(R.string.community_reply_input_placeholder))
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    enabled = replyText.isNotBlank() && !sending,
                                    onClick = {
                                        val uid = session.uid ?: return@IconButton
                                        sending = true
                                        scope.launch {
                                            val result = CommunityRepository.createReply(
                                                postId = postId,
                                                uid = uid,
                                                authorName = session.displayName ?: "anon",
                                                authorPhoto = session.photoUrl,
                                                body = replyText,
                                            )
                                            sending = false
                                            result.fold(
                                                onSuccess = {
                                                    replyText = ""
                                                    refreshTick++
                                                },
                                                onFailure = { t ->
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.community_reply_failed,
                                                            t.message ?: "",
                                                        ),
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                },
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        stringResource(R.string.community_reply_send),
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                stringResource(R.string.community_signin_required),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    if (deletePostConfirm) {
        AlertDialog(
            onDismissRequest = { deletePostConfirm = false },
            title = { Text(stringResource(R.string.community_delete_confirm_title)) },
            text = { Text(stringResource(R.string.community_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deletePostConfirm = false
                    val uid = session.uid ?: return@TextButton
                    scope.launch {
                        CommunityRepository.softDeletePost(postId, uid).fold(
                            onSuccess = { onBack() },
                            onFailure = { t ->
                                Toast.makeText(
                                    context,
                                    t.message ?: "",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                }) { Text(stringResource(R.string.community_delete_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePostConfirm = false }) {
                    Text(stringResource(R.string.community_delete_no))
                }
            },
        )
    }

    val replyToDelete = deleteReplyConfirm
    if (replyToDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteReplyConfirm = null },
            title = { Text(stringResource(R.string.community_delete_confirm_title)) },
            text = { Text(stringResource(R.string.community_delete_confirm_reply)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteReplyConfirm = null
                    val uid = session.uid ?: return@TextButton
                    scope.launch {
                        CommunityRepository.softDeleteReply(postId, replyToDelete, uid).fold(
                            onSuccess = { refreshTick++ },
                            onFailure = { t ->
                                Toast.makeText(
                                    context,
                                    t.message ?: "",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                }) { Text(stringResource(R.string.community_delete_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteReplyConfirm = null }) {
                    Text(stringResource(R.string.community_delete_no))
                }
            },
        )
    }

    // rc84: Play UGC policy — report + block dialogs.
    val reporting = reportTarget
    val reporterUid = session.uid
    if (reporting != null && reporterUid != null) {
        CommunityReportDialog(
            target = reporting,
            reporterUid = reporterUid,
            onDismiss = { reportTarget = null },
        )
    }
    val blocking = blockTarget
    if (blocking != null) {
        BlockUserDialog(
            target = blocking,
            onConfirm = {
                viewModel.blockUser(blocking.targetUid, blocking.authorName)
                blockTarget = null
                Toast.makeText(
                    context,
                    context.getString(R.string.community_blocked_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                // rc84: if the whole thread is authored by the user we just
                // blocked, there's nothing left to show — leave the screen
                // (the feed filter hides the post from now on).
                if (post?.uid == blocking.targetUid) onBack()
            },
            onDismiss = { blockTarget = null },
        )
    }
}

@Composable
private fun OriginalPost(
    post: CommunityPost,
    // rc84: null hides the report/block overflow (own or deleted content).
    moderationTarget: ModerationTarget?,
    onReport: (ModerationTarget) -> Unit,
    onBlock: (ModerationTarget) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    topicIcon(post.topic),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(topicLabelRes(post.topic)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text("•", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeTime(context, post.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // rc84: report/block overflow (Play UGC policy).
                if (moderationTarget != null) {
                    Spacer(Modifier.weight(1f))
                    ModerationOverflow(moderationTarget, onReport, onBlock)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (post.deletedAt != null) stringResource(R.string.community_post_deleted)
                else post.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.community_post_by_author, post.authorName.ifBlank { "anon" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (post.deletedAt == null) {
                BBCodeText(body = post.body, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReplyCard(
    reply: CommunityReply,
    isOwner: Boolean,
    onDelete: () -> Unit,
    // rc84: null hides the report/block overflow (own or deleted content).
    moderationTarget: ModerationTarget?,
    onReport: (ModerationTarget) -> Unit,
    onBlock: (ModerationTarget) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reply.authorName.ifBlank { "anon" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text("•", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeTime(context, reply.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (isOwner && reply.deletedAt == null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.community_delete_reply_action),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // rc84: report/block overflow on others' replies
                // (Play UGC policy); mutually exclusive with the owner
                // delete button above.
                if (moderationTarget != null) {
                    ModerationOverflow(moderationTarget, onReport, onBlock)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (reply.deletedAt != null) {
                Text(
                    stringResource(R.string.community_reply_deleted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BBCodeText(body = reply.body, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
