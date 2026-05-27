package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/**
 * Visual state of the Gemini Q&A sheet. Driven by MainViewModel.askGemini
 * (Task 10 — adds the state holder). The sealed class lets Compose switch
 * branches without flag spaghetti.
 */
sealed class GeminiQaState {
    /** Initial state — show the suggestion chips. */
    object Idle : GeminiQaState()
    /** Awaiting Gemini's reply — show the spinner. */
    object Loading : GeminiQaState()
    /** Reply received — show the text + remaining-queries chip. */
    data class Reply(val text: String, val remainingQueries: Int) : GeminiQaState()
    /** Network/auth/quota failure — show the error message. */
    data class Error(val message: String) : GeminiQaState()
}

/**
 * RC65: modal bottom sheet that opens when the user taps the top-bar
 * sparkle. Text input + suggestion chips for this task; mic button +
 * voice input land in Task 11.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiQaSheet(
    state: GeminiQaState,
    suggestions: List<String>,
    onSendPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var prompt by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.gemini_qa_sheet_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            when (state) {
                is GeminiQaState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.gemini_qa_loading))
                    }
                }
                is GeminiQaState.Reply -> {
                    Text(state.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.gemini_qa_queries_left, state.remainingQueries),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is GeminiQaState.Error -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                GeminiQaState.Idle -> {
                    Text(
                        stringResource(R.string.gemini_qa_suggestions_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { onSendPrompt(suggestion) },
                            label = { Text(suggestion, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.gemini_qa_input_placeholder)) },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (prompt.isNotBlank()) {
                            onSendPrompt(prompt)
                            prompt = ""
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.gemini_qa_send_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
