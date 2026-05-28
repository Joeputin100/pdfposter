package com.posterpdf.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * H-P2.3 — FAQ screen.
 *
 * Seven plain-English questions covering credits, offline use, the credit-
 * exhaustion case, generation time, custom paper sizes, and data deletion.
 * Tap a row to expand its answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqScreen(onBack: () -> Unit) {
    val faqs = listOf(
        stringResource(R.string.faq_q_credits_inline) to stringResource(R.string.faq_a_credits_inline),
        stringResource(R.string.faq_q_credit_value_inline) to stringResource(R.string.faq_a_credit_value_inline),
        stringResource(R.string.faq_q_offline_inline) to stringResource(R.string.faq_a_offline_inline),
        stringResource(R.string.faq_q_out_of_credits_inline) to stringResource(R.string.faq_a_out_of_credits_inline),
        stringResource(R.string.faq_q_pdf_time_inline) to stringResource(R.string.faq_a_pdf_time_inline),
        stringResource(R.string.faq_q_custom_paper_inline) to stringResource(R.string.faq_a_custom_paper_inline),
        stringResource(R.string.faq_q_data_deletion_inline) to stringResource(R.string.faq_a_data_deletion_inline),
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.faq_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.faq_back_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            faqs.forEachIndexed { index, (q, a) ->
                FaqRow(question = q, answer = a, defaultExpanded = index == 0)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FaqRow(question: String, answer: String, defaultExpanded: Boolean) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(answer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
