package com.repeatkit.fixtures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ComposeBasicActivity : ComponentActivity() {

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FixtureLog.event("compose_basic", "open", "compose_basic")

        setContent {
            var wifiChecked by remember { mutableStateOf(false) }

            Scaffold(
                contentWindowInsets = WindowInsets.systemBars,
                bottomBar = { EventLogFooter() }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Compose basic",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Button(
                        onClick = { FixtureLog.event("compose_basic", "tap", "btn_save") },
                        modifier = Modifier
                            .testTag("btn_save")
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Save")
                    }

                    IconButton(
                        onClick = { FixtureLog.event("compose_basic", "tap", "btn_gear") },
                        modifier = Modifier
                            .testTag("btn_gear")
                            .padding(bottom = 16.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings gear"
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.Gray)
                            .testTag("icon_unlabeled")
                            .clickable {
                                FixtureLog.event("compose_basic", "tap", "icon_unlabeled")
                            }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .testTag("sw_wifi")
                            .padding(bottom = 16.dp)
                    ) {
                        Text(text = "Wi-Fi", modifier = Modifier.padding(end = 8.dp))
                        Switch(
                            checked = wifiChecked,
                            onCheckedChange = { checked ->
                                wifiChecked = checked
                                FixtureLog.event(
                                    "compose_basic",
                                    "toggle",
                                    "sw_wifi",
                                    if (checked) "on" else "off"
                                )
                            }
                        )
                    }

                    Text(
                        text = "Hold me",
                        modifier = Modifier
                            .testTag("txt_hold")
                            .background(Color(0xFFDDDDDD))
                            .padding(16.dp)
                            .combinedClickable(
                                onClick = {
                                    FixtureLog.event("compose_basic", "tap", "txt_hold")
                                },
                                onLongClick = {
                                    FixtureLog.event("compose_basic", "long_press", "txt_hold")
                                }
                            )
                    )
                }
            }
        }
    }
}
