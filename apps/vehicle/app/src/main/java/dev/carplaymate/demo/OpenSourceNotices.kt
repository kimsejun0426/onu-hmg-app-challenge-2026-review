package dev.carplaymate.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface NoticesContent {
    object Loading : NoticesContent
    object Unavailable : NoticesContent
    data class Ready(val paragraphs: List<String>) : NoticesContent
}

@Composable
internal fun OpenSourceNotices(modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    var attempt by remember { mutableStateOf(0) }
    var content by remember(assets) { mutableStateOf<NoticesContent>(NoticesContent.Loading) }
    LaunchedEffect(assets, attempt) {
        content = NoticesContent.Loading
        content = withContext(Dispatchers.IO) {
            try {
                val text = assets.open("legal/THIRD_PARTY_NOTICES.txt")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (text.isBlank()) NoticesContent.Unavailable
                else NoticesContent.Ready(text.replace("\r\n", "\n").split("\n\n"))
            } catch (_: IOException) {
                NoticesContent.Unavailable
            } catch (_: SecurityException) {
                NoticesContent.Unavailable
            }
        }
    }

    Column(modifier.testTag("family.notices")) {
        when (val current = content) {
            NoticesContent.Loading -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp).testTag("notices.loading"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                CircularProgressIndicator()
                Text("고지를 불러오고 있어요.", style = MaterialTheme.typography.bodyMedium)
            }
            NoticesContent.Unavailable -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp).testTag("notices.error"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    "오픈소스 고지를 불러오지 못했어요. 다시 시도해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { attempt += 1 },
                    modifier = Modifier.heightIn(min = 72.dp).testTag("notices.retry"),
                ) {
                    Text("다시 불러오기")
                }
            }
            is NoticesContent.Ready -> LazyColumn(
                Modifier.fillMaxSize().testTag("notices.content"),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(current.paragraphs) { paragraph ->
                    Text(
                        paragraph,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PlaymateColors.Ink,
                    )
                }
            }
        }
    }
}
