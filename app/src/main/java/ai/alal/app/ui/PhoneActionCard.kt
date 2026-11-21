package ai.alal.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.alal.app.personal.PhoneAction
import ai.alal.app.ui.theme.AlalBlue

/**
 * Confirm card for an action the model proposed. Nothing touches the phone
 * until the user taps Confirm; missing permissions are requested first.
 */
@Composable
fun PhoneActionCard(
    action: PhoneAction,
    state: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) onConfirm() }

    OutlinedCard(
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AlalBlue.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(action), contentDescription = null, tint = AlalBlue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(action.summary, fontWeight = FontWeight.Medium)
                when {
                    state == "done" -> Text("Done", style = MaterialTheme.typography.labelSmall, color = AlalBlue)
                    state == "dismissed" -> Text("Dismissed", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    state?.startsWith("failed") == true ->
                        Text(state.removePrefix("failed: "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (state == null || state.startsWith("failed")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.Gray) }
                TextButton(onClick = {
                    val missing = action.permissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) onConfirm() else permissionLauncher.launch(missing.toTypedArray())
                }) { Text(if (state == null) "Confirm" else "Retry", color = AlalBlue, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private fun iconFor(action: PhoneAction): ImageVector = when (action) {
    is PhoneAction.Alarm -> Icons.Outlined.Alarm
    is PhoneAction.Timer -> Icons.Outlined.Timer
    is PhoneAction.Reminder -> Icons.Outlined.NotificationsActive
    is PhoneAction.Event -> Icons.Outlined.Event
    is PhoneAction.Todo -> Icons.Outlined.Checklist
    is PhoneAction.Sms -> Icons.Outlined.Sms
    is PhoneAction.Email -> Icons.Outlined.Email
    is PhoneAction.AppMessage -> Icons.Outlined.Chat
}
