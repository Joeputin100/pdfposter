package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/**
 * RC5 — in-app language picker.
 *
 * Lists "System default" plus the 10 locales we ship strings.xml files for.
 * Selecting an option immediately calls AppCompatDelegate.setApplicationLocales
 * (handled by the caller); AppCompat takes care of recreating the activity.
 *
 * Reads the current selection from AppCompatDelegate.getApplicationLocales()
 * so the radio button reflects the active locale on first open.
 */
@Composable
fun LanguagePickerDialog(
    onDismiss: () -> Unit,
    onPick: (tag: String) -> Unit,
) {
    val options: List<Pair<String, Int>> = listOf(
        "" to R.string.language_system_default,
        "en" to R.string.language_en,
        "es" to R.string.language_es,
        "de" to R.string.language_de,
        "fr" to R.string.language_fr,
        "pt-BR" to R.string.language_pt_br,
        "ru" to R.string.language_ru,
        "ja" to R.string.language_ja,
        "zh-CN" to R.string.language_zh_cn,
        "hi" to R.string.language_hi,
        "ar" to R.string.language_ar,
    )

    // RC17: read from LocaleManager on API 33+ (the platform source of
    // truth) and fall back to AppCompatDelegate on older. The pre-RC17
    // path read only AppCompatDelegate, which on a ComponentActivity-
    // hosted app falls out of sync with the platform's actual locale,
    // so the dialog kept showing "System default" even after a switch.
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val lm = context.getSystemService(android.app.LocaleManager::class.java)
            val l = lm?.applicationLocales
            if (l == null || l.isEmpty) "" else l.toLanguageTags().substringBefore(',')
        } else {
            val active = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            if (active.isEmpty) "" else active.toLanguageTags().substringBefore(',')
        }
    }
    var selected by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { (tag, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == tag,
                                onClick = { selected = tag },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == tag, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
