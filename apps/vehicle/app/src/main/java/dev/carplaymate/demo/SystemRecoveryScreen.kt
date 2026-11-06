package dev.carplaymate.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** No choices, transcript or setup while the established voice host continues. */
@Composable
internal fun VoiceOnlySessionScreen() {
    RecoveryPage("voice-only-session") {
        Text("오누", style = MaterialTheme.typography.titleMedium, color = PlaymateColors.Forest)
        Text("목소리로 함께하고 있어요", style = MaterialTheme.typography.headlineMedium)
        Text("화면은 잠시 쉬어요. 오누의 질문 뒤에 말로 답해 주세요.",
            style = MaterialTheme.typography.bodyLarge, color = PlaymateColors.Muted)
    }
}

@Composable
internal fun VehicleRestrictedScreen(state: VehicleAccessState) {
    RecoveryPage("vehicle-restricted") {
        Text("오누", style = MaterialTheme.typography.titleMedium, color = PlaymateColors.Forest)
        Text(
            if (state == VehicleAccessState.UNKNOWN) "차량 상태를 확인하고 있어요" else "안전하게 멈춘 뒤에 만나요",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            if (state == VehicleAccessState.UNKNOWN) "확인이 끝날 때까지 화면과 소리를 잠시 쉬어요."
            else "차량에서 이용할 수 있는 상태가 되면 다시 함께해요. 이야기는 이어서 할 수 있어요.",
            style = MaterialTheme.typography.bodyLarge,
            color = PlaymateColors.Muted,
        )
    }
}

@Composable
internal fun StorageRecoveryScreen(
    ui: DeveloperUiState,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    RecoveryPage("storage-recovery") {
        Text("기록을 확인할 수 없어요", style = MaterialTheme.typography.headlineMedium)
        Text(ui.message, style = MaterialTheme.typography.bodyLarge, color = PlaymateColors.Muted)
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = onRetry,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("storage-retry"),
        ) {
            Text("기록 다시 확인하기")
        }
        OutlinedButton(
            onClick = { confirmDelete = true },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("storage-delete"),
        ) {
            Text("기기의 가족 기록 삭제")
        }
        Text(
            "삭제하기 전에는 앱 업데이트도 확인해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = PlaymateColors.Muted,
        )
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("가족 기록을 모두 지울까요?") },
            text = { Text("이 기기의 별명, 관심사, 진행 중인 모험과 기억을 모두 지워요. 지운 기록은 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("모두 삭제하기")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("기록 보존하기")
                }
            },
        )
}

@Composable
private fun RecoveryPage(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize().testTag(tag), color = PlaymateColors.Ivory) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
    }
}
