package io.github.zrpc.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zrpc.log.AppLog
import io.github.zrpc.log.LogLevel
import io.github.zrpc.ui.components.ZrpcScaffold

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries by AppLog.entries.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.scrollToItem(entries.lastIndex) }

    ZrpcScaffold(
        title = "Logs",
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.export())
                ctx.startActivity(Intent.createChooser(send, "Share logs (token-free)"))
            }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = AppLog::clear) { Icon(Icons.Filled.DeleteSweep, "Clear") }
        },
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp, start = 12.dp, end = 12.dp),
        ) {
            if (entries.isEmpty()) item { Text("No logs yet.", modifier = Modifier.padding(8.dp)) }
            items(entries) { e ->
                val color = when (e.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    e.format(),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
