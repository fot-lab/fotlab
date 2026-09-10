package io.github.fotlab.fotlab.feature.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.fotlab.fotlab.R

/**
 * Rename dialog for the single-selection edit action (`FOTLAB-UIXDES-000004`): prefilled with
 * the node's current name, and confirms only when the trimmed name is non-empty. The caller
 * performs the actual rename through [onConfirm]; this file owns no data logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.library_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(text = stringResource(id = R.string.library_rename_label)) },
                singleLine = true,
                isError = trimmed.isEmpty(),
                supportingText = if (trimmed.isEmpty()) {
                    { Text(text = stringResource(id = R.string.library_rename_empty)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = { onConfirm(trimmed) },
            ) {
                Text(text = stringResource(id = R.string.library_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
    )
}
