package com.posterpdf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.posterpdf.MainViewModel
import com.posterpdf.R
import com.posterpdf.data.backend.CommunityRepository
import com.posterpdf.data.backend.CommunityRepository.CommunityPost
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * RC44 — Community board feed.
 *
 * Public-read, write-gated to registered users. Topic filter chips at
 * the top, post cards in a LazyColumn below. The compose FAB is hidden
 * for anonymous users (a sign-in banner takes its place).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onCompose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRegistered = !viewModel.authSession.isAnonymous && viewModel.authSession.signedIn

    var selectedTopic by remember { mutableStateOf<String?>(null) }
    var posts by remember { mutableStateOf<List<CommunityPost>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    // rc84: Play UGC policy — report + block. Posts from locally-blocked
    // authors are filtered out of the feed; the overflow menu on each
    // card feeds these two dialog targets.
    val sessionUid = viewModel.authSession.uid
    val blockedIds = viewModel.blockedUsers.keys
    var reportTarget by remember { mutableStateOf<ModerationTarget?>(null) }
    var blockTarget by remember { mutableStateOf<ModerationTarget?>(null) }

    LaunchedEffect(selectedTopic, refreshTick) {
        loading = true
        loadFailed = false
        CommunityRepository.fetchPosts(selectedTopic).fold(
            onSuccess = { posts = it },
            onFailure = { loadFailed = true; posts = emptyList() },
        )
        loading = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.community_screen_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.help_back_cd))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.community_refresh_cd))
                    }
                },
            )
        },
        floatingActionButton = {
            if (isRegistered) {
                ExtendedFloatingActionButton(
                    onClick = onCompose,
                    icon = {
                        Icon(
                            Icons.Default.Edit,
                            stringResource(R.string.community_compose_fab_cd),
                        )
                    },
                    text = { Text(stringResource(R.string.community_compose_fab_label)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Topic filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedTopic == null,
                    onClick = { selectedTopic = null },
                    label = { Text(stringResource(R.string.community_topic_all)) },
                )
                CommunityRepository.Topic.ALL.forEach { topic ->
                    FilterChip(
                        selected = selectedTopic == topic,
                        onClick = { selectedTopic = topic },
                        label = { Text(stringResource(topicLabelRes(topic))) },
                        leadingIcon = {
                            Icon(
                                topicIcon(topic),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }

            // Sign-in banner for anonymous users
            if (!isRegistered) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        stringResource(R.string.community_signin_required),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // Feed
            // rc84: filter blocked authors out of the feed (and use the
            // filtered list for the empty state so an all-blocked feed
            // reads as empty rather than rendering a blank list).
            val visiblePosts = posts.filter { it.uid !in blockedIds }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.community_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                visiblePosts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedTopic == null) stringResource(R.string.community_no_posts_all)
                        else stringResource(R.string.community_no_posts_topic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visiblePosts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            onClick = { onOpenPost(post.id) },
                            // rc84: report/block only on OTHER people's
                            // live posts — hidden on own + deleted content.
                            moderationTarget = if (
                                sessionUid != null && post.uid != sessionUid &&
                                post.uid.isNotBlank() && post.deletedAt == null
                            ) {
                                ModerationTarget(
                                    targetType = CommunityRepository.ReportType.POST,
                                    postId = post.id,
                                    replyId = null,
                                    targetUid = post.uid,
                                    authorName = post.authorName,
                                )
                            } else {
                                null
                            },
                            onReport = { reportTarget = it },
                            onBlock = { blockTarget = it },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }  // FAB clearance
                }
            }
        }
    }

    // rc84: Play UGC policy — report + block dialogs.
    val reporting = reportTarget
    if (reporting != null && sessionUid != null) {
        CommunityReportDialog(
            target = reporting,
            reporterUid = sessionUid,
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
            },
            onDismiss = { blockTarget = null },
        )
    }
}

@Composable
private fun PostCard(
    post: CommunityPost,
    onClick: () -> Unit,
    // rc84: null hides the report/block overflow (own or deleted content).
    moderationTarget: ModerationTarget?,
    onReport: (ModerationTarget) -> Unit,
    onBlock: (ModerationTarget) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Spacer(Modifier.height(6.dp))
            Text(
                if (post.deletedAt != null) stringResource(R.string.community_post_deleted)
                else post.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.community_post_by_author, post.authorName.ifBlank { "anon" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun topicIcon(topic: String): ImageVector = when (topic) {
    CommunityRepository.Topic.RELEASE_NOTES -> Icons.Default.Campaign
    CommunityRepository.Topic.FEATURE_REQUESTS -> Icons.Default.AutoAwesome
    CommunityRepository.Topic.TROUBLESHOOTING -> Icons.Default.Build
    else -> Icons.Default.Forum
}

internal fun topicLabelRes(topic: String): Int = when (topic) {
    CommunityRepository.Topic.RELEASE_NOTES -> R.string.community_topic_release_notes
    CommunityRepository.Topic.FEATURE_REQUESTS -> R.string.community_topic_feature_requests
    CommunityRepository.Topic.TROUBLESHOOTING -> R.string.community_topic_troubleshooting
    else -> R.string.community_topic_discussion
}

internal fun relativeTime(context: android.content.Context, date: Date): String {
    val delta = (System.currentTimeMillis() - date.time) / 1000
    return when {
        delta < 60 -> context.getString(R.string.community_relative_just_now)
        delta < 3600 -> context.getString(R.string.community_relative_minutes_ago, delta / 60)
        delta < 86400 -> context.getString(R.string.community_relative_hours_ago, delta / 3600)
        delta < 86400L * 30 -> context.getString(R.string.community_relative_days_ago, delta / 86400)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }
}
