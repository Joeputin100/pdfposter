package com.posterpdf.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.posterpdf.MainViewModel
import com.posterpdf.R
import com.posterpdf.data.backend.CommunityRepository
import kotlinx.coroutines.launch

/**
 * RC44 — community post composer. Shown only to registered users (the
 * feed FAB is hidden for anonymous users so they never reach this).
 *
 * BBCode is supported in the body — there's a hint line right under the
 * field showing the supported tags. Body cap matches the firestore.rules
 * bound (8000 chars) so an oversized post fails client-side rather than
 * round-tripping to a rules denial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityComposeScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = viewModel.authSession

    var isAdmin by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf(CommunityRepository.Topic.DISCUSSION) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var topicMenuOpen by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }

    LaunchedEffect(session.uid) {
        val uid = session.uid ?: return@LaunchedEffect
        isAdmin = CommunityRepository.isAdmin(uid)
    }

    val canPost = title.isNotBlank() && body.isNotBlank() && !posting
        && (topic != CommunityRepository.Topic.RELEASE_NOTES || isAdmin)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.community_compose_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.help_back_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Topic dropdown
            Box {
                OutlinedTextField(
                    value = stringResource(topicLabelRes(topic)),
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text(stringResource(R.string.community_compose_topic_label)) },
                    leadingIcon = {
                        Icon(topicIcon(topic), contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { topicMenuOpen = !topicMenuOpen }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    },
                )
                DropdownMenu(
                    expanded = topicMenuOpen,
                    onDismissRequest = { topicMenuOpen = false },
                ) {
                    CommunityRepository.Topic.ALL.forEach { t ->
                        val isReleaseNotes = (t == CommunityRepository.Topic.RELEASE_NOTES)
                        if (isReleaseNotes && !isAdmin) return@forEach
                        DropdownMenuItem(
                            text = { Text(stringResource(topicLabelRes(t))) },
                            leadingIcon = { Icon(topicIcon(t), contentDescription = null) },
                            onClick = {
                                topic = t
                                topicMenuOpen = false
                            },
                        )
                    }
                }
            }

            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.community_compose_subject_label)) },
                placeholder = { Text(stringResource(R.string.community_compose_subject_placeholder)) },
                singleLine = true,
            )

            // Body
            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(8000) },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                label = { Text(stringResource(R.string.community_compose_body_label)) },
                placeholder = { Text(stringResource(R.string.community_compose_body_placeholder)) },
            )

            // BBCode hint
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.community_bbcode_hint),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Submit
            Button(
                onClick = {
                    val uid = session.uid ?: return@Button
                    posting = true
                    scope.launch {
                        val result = CommunityRepository.createPost(
                            uid = uid,
                            authorName = session.displayName ?: "anon",
                            authorPhoto = session.photoUrl,
                            topic = topic,
                            title = title,
                            body = body,
                        )
                        posting = false
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.community_post_success),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onBack()
                            },
                            onFailure = { t ->
                                val msg = t.message ?: context.getString(R.string.support_unknown_error)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.community_post_failed, msg),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                },
                enabled = canPost,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    if (posting) stringResource(R.string.community_compose_posting)
                    else stringResource(R.string.community_compose_post),
                )
            }
        }
    }
}
