package com.repeatkit.fixtures

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EventLogFooter(modifier: Modifier = Modifier) {
    val entries = remember { mutableStateListOf<String>() }
    DisposableEffect(Unit) {
        val removeListener = FixtureLog.addListener { newEntries ->
            entries.clear()
            entries.addAll(newEntries)
        }
        onDispose { removeListener() }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF222222),
        contentColor = Color(0xFF00FF66)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Event Log (last 5 entries)",
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            val lines = if (entries.isEmpty()) {
                "No events yet\n\n\n\n"
            } else {
                val list = entries.takeLast(5)
                val padded = list + List(maxOf(0, 5 - list.size)) { "" }
                padded.joinToString("\n")
            }
            Text(
                text = lines,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 5
            )
        }
    }
}
