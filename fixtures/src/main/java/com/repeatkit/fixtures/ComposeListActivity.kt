package com.repeatkit.fixtures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ComposeListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FixtureLog.event("compose_list", "open", "compose_list")

        setContent {
            var row45Logged by remember { mutableStateOf(false) }

            Scaffold(
                contentWindowInsets = WindowInsets.systemBars,
                bottomBar = { EventLogFooter() }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    Text(
                        text = "Compose list",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(60) { index ->
                            val itemNumber = index + 1

                            if (itemNumber == 45) {
                                LaunchedEffect(Unit) {
                                    if (!row45Logged) {
                                        row45Logged = true
                                        FixtureLog.event("compose_list", "scroll_reached", "row_45")
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("row_$itemNumber")
                                    .clickable {
                                        FixtureLog.event("compose_list", "tap", "row_$itemNumber")
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Item $itemNumber",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "Subtitle",
                                        color = Color.Gray,
                                        fontSize = 14.sp
                                    )
                                }

                                Button(
                                    onClick = {
                                        FixtureLog.event(
                                            "compose_list",
                                            "tap",
                                            "row_${itemNumber}_open"
                                        )
                                    },
                                    modifier = Modifier.testTag("row_${itemNumber}_open")
                                ) {
                                    Text("Open")
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        }
    }
}
