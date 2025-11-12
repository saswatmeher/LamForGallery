package com.example.lamforgallery.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

@Composable
fun DeleteConfirmationDialog(
    itemCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete $itemCount item${if (itemCount > 1) "s" else ""}?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text("Deleted items can be recovered from trash album within 30 days.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete $itemCount item${if (itemCount > 1) "s" else ""}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
