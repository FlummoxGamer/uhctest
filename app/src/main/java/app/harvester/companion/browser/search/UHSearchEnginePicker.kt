package app.harvester.companion.browser.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.SearchEnginePrefs
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UHSearchEnginePicker(onDismiss: () -> Unit, onSelect: () -> Unit) {
    val context = LocalContext.current
    val currentEntry by SearchEnginePrefs.activeEngineFlow(context).collectAsState()
    val current = currentEntry.id
    val engines = SearchEnginePrefs.all(context)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Search Engine",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn {
                items(engines, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (entry.id == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable {
                                SearchEnginePrefs.setEngine(context, entry.id)
                                onSelect()
                                onDismiss()
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = entry.iconUrl,
                            contentDescription = entry.name,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = entry.name,
                            color = if (entry.id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 16.dp).weight(1f)
                        )
                    }
                }
            }
        }
    }
}
