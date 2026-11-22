package team.themoment.axia.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun UpdateNotice(
    state: UpdateState,
    onInstall: (ReleaseInstaller) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == UpdateState.None) return
    Surface(modifier = modifier.width(300.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                UpdateState.None -> {
                    Unit
                }

                is UpdateState.Available -> {
                    Text(text = "새 버전 ${state.installer.version}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "업데이트하면 앱이 잠시 종료되었다가 새 버전으로 다시 실행됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    NoticeActions(
                        confirmLabel = "업데이트",
                        dismissLabel = "나중에",
                        onConfirm = { onInstall(state.installer) },
                        onDismiss = onDismiss,
                    )
                }

                is UpdateState.Downloading -> {
                    Text(text = "새 버전 ${state.installer.version} 내려받는 중", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text(text = "${(state.progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.Failed -> {
                    Text(
                        text = "업데이트 실패",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(text = state.reason, style = MaterialTheme.typography.bodySmall)
                    NoticeActions(
                        confirmLabel = "다시 시도",
                        dismissLabel = "닫기",
                        onConfirm = { onInstall(state.installer) },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeActions(
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onDismiss) { Text(dismissLabel) }
        Button(onClick = onConfirm) { Text(confirmLabel) }
    }
}
