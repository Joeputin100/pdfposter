package com.posterpdf.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.posterpdf.MainViewModel
import com.posterpdf.R
import com.posterpdf.data.backend.CommunityRepository
import kotlinx.coroutines.launch

/**
 * rc84: Play UGC policy — report + block affordances for the community
 * board, shared by CommunityScreen (feed cards) and CommunityPostScreen
 * (original post + replies):
 *   - [ModerationOverflow]: compact overflow menu (Report / Block user),
 *     shown only on OTHER people's live content (never on own posts —
 *     self-report/self-block make no sense and are guarded again in
 *     MainViewModel.blockUser).
 *   - [CommunityReportDialog]: reason radio group + optional detail,
 *     writes a create-only document to /reports (firestore.rules).
 *   - [BlockUserDialog]: confirm before adding the author to the local
 *     block list (MainViewModel.blockedUsers).
 *   - [BlockedUsersDialog]: the unblock path, opened from the settings
 *     drawer's "Blocked users" row.
 */

/** rc84: what the overflow menu is pointing at. [replyId] null == post. */
internal data class ModerationTarget(
    val targetType: String,
    val postId: String,
    val replyId: String?,
    val targetUid: String,
    val authorName: String,
)

@Composable
internal fun ModerationOverflow(
    target: ModerationTarget,
    onReport: (ModerationTarget) -> Unit,
    onBlock: (ModerationTarget) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.community_more_actions_cd),
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.community_report_action)) },
                leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                onClick = {
                    expanded = false
                    onReport(target)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.community_block_action)) },
                leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                onClick = {
                    expanded = false
                    onBlock(target)
                },
            )
        }
    }
}

@Composable
internal fun CommunityReportDialog(
    target: ModerationTarget,
    reporterUid: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(CommunityRepository.ReportReason.SPAM) }
    var detail by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    val reasons = listOf(
        CommunityRepository.ReportReason.SPAM to R.string.community_report_reason_spam,
        CommunityRepository.ReportReason.OFFENSIVE to R.string.community_report_reason_offensive,
        CommunityRepository.ReportReason.OTHER to R.string.community_report_reason_other,
    )

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.community_report_dialog_title)) },
        text = {
            Column {
                reasons.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reason = value },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = reason == value,
                            onClick = { reason = value },
                        )
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.community_report_detail_placeholder))
                    },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    submitting = true
                    scope.launch {
                        val result = CommunityRepository.submitReport(
                            reporterUid = reporterUid,
                            targetType = target.targetType,
                            postId = target.postId,
                            replyId = target.replyId,
                            targetUid = target.targetUid,
                            reason = reason,
                            detail = detail,
                        )
                        submitting = false
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.community_report_submitted),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            },
                            onFailure = { t ->
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.community_report_failed,
                                        t.message ?: "",
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                },
            ) { Text(stringResource(R.string.community_report_submit)) }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun BlockUserDialog(
    target: ModerationTarget,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.community_block_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.community_block_confirm,
                    target.authorName.ifBlank { "anon" },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.community_block_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun BlockedUsersDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val blocked = viewModel.blockedUsers
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_blocked_users)) },
        text = {
            if (blocked.isEmpty()) {
                Text(
                    stringResource(R.string.blocked_users_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    blocked.forEach { (uid, name) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                // Display name captured at block time; fall
                                // back to a truncated uid when unavailable.
                                name.ifBlank { uid.take(12) + "…" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { viewModel.unblockUser(uid) }) {
                                Text(stringResource(R.string.blocked_users_unblock))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}
