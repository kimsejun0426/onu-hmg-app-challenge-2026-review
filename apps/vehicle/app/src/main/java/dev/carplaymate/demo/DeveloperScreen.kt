package dev.carplaymate.demo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun DeveloperScreen(model: DeveloperViewModel) {
    val ui by model.ui.collectAsState()
    val ttsStatus by model.speechStatus.collectAsState()
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.microphonePermissionResult(it.values.all { granted -> granted })
    }
    val outputPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.retrySpeech()
    }
    var url by remember { mutableStateOf("http://10.0.2.2:8000") }
    // Intentionally not rememberSaveable: bearer token and utterances never go to saved state.
    var token by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("민준") }
    var second by remember { mutableStateOf("서윤") }
    var twoPlayers by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    val snapshot = ui.snapshot
    val listening = !ui.voiceInput && !ui.quiet && !ui.busy && snapshot?.phase == "LISTENING"
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("오누 · 개발 도구", style = MaterialTheme.typography.headlineMedium)
            Text("차가 가족의 동반자가 되는 시간", style = MaterialTheme.typography.titleMedium)
            Text("첫 모험 · 공룡 연구소의 사라진 알", style = MaterialTheme.typography.titleSmall)
            Text("성인 개발자용 · 정차 환경에서 시험하는 화면입니다. Pleos 연결과 주행 중 사용은 아직 검증하지 않았어요.")
            OutlinedTextField(url, { url = it }, label = { Text("API 서버") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !ui.busy && ui.quiet)
            OutlinedTextField(token, { token = it }, label = { Text("개발용 Bearer 토큰 · 메모리에만 보관") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !ui.busy && ui.quiet)
            Button(onClick = { model.configure(url, token); token = "" }, enabled = !ui.busy && ui.quiet) {
                Text("서버 설정 적용")
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(first, { first = it }, label = { Text("첫 별명") }, singleLine = true,
                    modifier = Modifier.weight(1f), enabled = !ui.busy)
                if (twoPlayers) OutlinedTextField(second, { second = it }, label = { Text("둘째 별명") },
                    singleLine = true, modifier = Modifier.weight(1f), enabled = !ui.busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = twoPlayers, onCheckedChange = { twoPlayers = it }, enabled = !ui.busy)
                Text(if (twoPlayers) "두 명의 교대 역할" else "혼자 두 역할")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.simulation, onCheckedChange = model::useSimulation, enabled = !ui.busy)
                Text(if (ui.simulation) "재생 완료를 개발자 버튼으로 모의 처리" else "기기의 한국어 TTS로 실제 재생")
            }
            Text(ttsStatus, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                val required = model.requiredOutputPermissions()
                if (required.isEmpty()) model.retrySpeech() else {
                    model.pause("USER")
                    outputPermission.launch(required)
                }
            }, enabled = !ui.busy && ui.quiet) { Text("음성 출력 권한 및 준비 다시 확인") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.voiceInput, onCheckedChange = { enabled ->
                    if (!enabled) {
                        model.useVoiceInput(false)
                    } else if (model.voiceInputSupported()) {
                        val required = model.requiredVoicePermissions()
                        if (required.isEmpty()) {
                            model.useVoiceInput(true)
                        } else {
                            model.pause("MIC_PERMISSION")
                            microphonePermission.launch(required)
                        }
                    }
                }, enabled = !ui.busy)
                Text(if (ui.voiceInput) "기기 음성 입력" else "모의 입력 · 마이크 꺼짐")
            }
            Text("기기 음성 입력을 선택하면 실제 TTS로 안내하고, 안내 완료 후 마이크를 한 차례씩 켭니다. 한국어 온디바이스 인식을 사용해요. " +
                if (ui.useLocal) "현재 인식된 문장은 이 기기 안에서 처리해요." else "현재 인식된 문장은 설정한 게임 서버로 전달해요.",
                style = MaterialTheme.typography.bodySmall)
            Text("안내 중에는 마이크가 꺼져 있어요. 이때는 화면의 ‘그만’ 버튼으로 중단해 주세요. 선택된 음성 서비스의 권한과 한국어 오프라인 인식 지원이 필요합니다.",
                style = MaterialTheme.typography.bodySmall)
            Text(ui.inputStatus,
                color = if (ui.microphoneListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { model.start(first, if (twoPlayers) second else null) },
                    enabled = ui.connected && !ui.busy) { Text("새 모험") }
                OutlinedButton(onClick = model::restore, enabled = ui.connected && !ui.busy) { Text("진행 확인") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { model.pause() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF752F37))) { Text("그만 · 즉시 조용히") }
                OutlinedButton(onClick = model::resume, enabled = ui.connected && !ui.busy && ui.quiet,
                    modifier = Modifier.weight(1f)) { Text("이어하기") }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ui.quiet) "조용히 쉬는 중" else "모험 진행 중", style = MaterialTheme.typography.titleMedium)
                    Text(ui.message)
                    if (snapshot != null) {
                        Text("${snapshot.status} / ${snapshot.phase} · v${snapshot.version}", style = MaterialTheme.typography.bodySmall)
                        Text("장면 ${snapshot.game.optString("scene_id")} · 차례 ${snapshot.game.optString("expected_player_id")}",
                            style = MaterialTheme.typography.bodySmall)
                        snapshot.speechPlan?.segments?.forEach { Text(it.text) }
                    }
                }
            }
            if (ui.simulation && snapshot?.phase in setOf("PROMPTING", "FEEDBACK")) {
                OutlinedButton(onClick = model::simulatePlaybackCompletion,
                    enabled = !ui.busy && !ui.quiet, modifier = Modifier.fillMaxWidth()) {
                    Text("개발용: 재생 완료 사건 보내기")
                }
            }
            if (!ui.voiceInput) {
                OutlinedTextField(text, { text = it }, label = { Text("인식 결과 모의 입력") },
                    placeholder = { Text("민준, 물가로 갈래") }, supportingText = { Text("개발 발화는 512자 이내로 입력해 주세요.") },
                    isError = text.length > 512, modifier = Modifier.fillMaxWidth(), enabled = listening)
                Button(onClick = { model.submit(text); text = "" },
                    enabled = listening && text.isNotBlank() && text.length <= 512, modifier = Modifier.fillMaxWidth()) {
                    Text("최종 발화 전달")
                }
            }
            Text("다시 · 힌트 · 생각 중이야도 입력할 수 있어요. 앱을 벗어나면 출력이 멈추고, 복원 후에는 명시적으로 이어해야 해요.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
