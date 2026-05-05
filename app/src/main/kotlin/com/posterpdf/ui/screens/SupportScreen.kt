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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.Build
import android.widget.Toast
import com.posterpdf.BuildConfig
import com.posterpdf.MainViewModel

/**
 * RC17 — Support / feedback form.
 *
 * Submits a Firestore document at /support/{auto-id} via SupportRepository.
 * The "include diagnostic info" checkbox is opt-in (defaulted on so most
 * users help us reproduce the bug, but they can uncheck it). Below the
 * checkbox is a collapsible breakdown of EXACTLY what's included and
 * what isn't, so the consent is informed rather than rubber-stamped.
 *
 * Categories deliberately match the four most common support buckets
 * we expect: bug, feature request, billing, other. The dropdown is
 * compact so the form fits on a single screen on a typical phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var subject by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Bug report") }
    var description by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Send feedback", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            Text(
                "Tell us what went wrong, what you wish the app did, " +
                    "or what's confusing. We read every report.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Subject
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Subject") },
                placeholder = { Text("One line — what's the issue?") },
                singleLine = true,
            )

            // Category dropdown
            CategoryDropdown(category, onChange = { category = it })

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(4000) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text("Tell us more") },
                placeholder = {
                    Text(
                        "What were you doing when it happened? " +
                            "What did you expect vs. what you saw?",
                    )
                },
            )

            // Diagnostics opt-in
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeDiagnostics,
                            onCheckedChange = { includeDiagnostics = it },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Include diagnostic info",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "This helps us reproduce the bug faster.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { detailsExpanded = !detailsExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                if (detailsExpanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (detailsExpanded) "Hide details"
                                else "What's included / what's not",
                            )
                        }
                    }
                    if (detailsExpanded) {
                        Spacer(Modifier.height(8.dp))
                        DiagnosticsBreakdown()
                    }
                }
            }

            // Submit
            val canSubmit = subject.isNotBlank() && description.isNotBlank() && !submitting
            Button(
                onClick = {
                    submitting = true
                    viewModel.submitSupport(
                        context = context,
                        subject = subject,
                        category = category,
                        description = description,
                        includeDiagnostics = includeDiagnostics,
                        onResult = { result ->
                            submitting = false
                            // RC20: explicit toast + screen close so the user
                            // gets immediate confirmation. Previously the
                            // submitted ticket replaced the form with a
                            // SuccessPanel, but real-device testing showed
                            // users tapping back before noticing it; failures
                            // also went silent (errorMessage surfaces in the
                            // main scaffold, not on the support screen).
                            result.fold(
                                onSuccess = { id ->
                                    Toast.makeText(
                                        context,
                                        "Thanks — feedback sent (${id.take(6)})",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onBack()
                                },
                                onFailure = { t ->
                                    val msg = t.message ?: "unknown error"
                                    Toast.makeText(
                                        context,
                                        "Couldn't send feedback: $msg",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = canSubmit,
            ) {
                if (submitting) Text("Sending…") else Text("Send feedback")
            }

            Text(
                if (viewModel.authSession.email != null)
                    "Replies go to ${viewModel.authSession.email}."
                else
                    "Sign in with Google to get a reply by email.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(value: String, onChange: (String) -> Unit) {
    val categories = listOf("Bug report", "Feature request", "Billing question", "Other")
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Type") },
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, "Pick category")
                }
            },
            singleLine = true,
        )
        // Invisible click-catcher over the field so tapping anywhere
        // expands the menu, not just the trailing icon.
        Surface(
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier
                .matchParentSize(),
            onClick = { expanded = true },
        ) {}
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat) },
                    onClick = {
                        onChange(cat)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsBreakdown() {
    // RC20: render the actual values that will be sent so the consent is
    // grounded in real data. Previously the bullets used a hypothetical
    // "Samsung SM-S908U on Android 14 / version 1.0-rc17" example which
    // confused users on different devices/builds — they couldn't tell if
    // they were consenting to the example or to their own data.
    val deviceLine = "${Build.MANUFACTURER} ${Build.MODEL} on Android ${Build.VERSION.RELEASE}"
    val versionLine = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BreakdownSection(
            title = "We send:",
            color = MaterialTheme.colorScheme.tertiary,
            items = listOf(
                "Your device: $deviceLine",
                "App version: $versionLine",
                "Last ~50 KB of your debug log — taps, image picks, " +
                    "error messages, upscale tile counts",
                "Your account ID and email if signed in, so we can link " +
                    "your report to backend records",
                "A timestamp",
            ),
        )
        BreakdownSection(
            title = "We do NOT send:",
            color = MaterialTheme.colorScheme.error,
            items = listOf(
                "Any photos you've imported into the app",
                "Any PDFs you've generated",
                "Files anywhere else on your phone",
                "Your phone number, contacts, or location",
                "A list of other apps installed on your phone",
                "Any cloud-stored posters or their content",
            ),
        )
        Text(
            "Why this helps: most bugs depend on a specific sequence of " +
                "taps on a specific device. Without these clues we end up " +
                "asking you a dozen follow-up questions. With them we " +
                "can usually reproduce the issue on a matching test " +
                "device the same day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BreakdownSection(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    items: List<String>,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(Modifier.height(4.dp))
        items.forEach { item ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("• ", style = MaterialTheme.typography.bodySmall, color = color)
                Text(
                    item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

