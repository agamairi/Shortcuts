package com.repeatkit.fixtures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class TextComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FixtureLog.event("text_compose", "open", "text_compose")

        setContent {
            var searchText by remember { mutableStateOf("") }
            var messageText by remember { mutableStateOf("") }
            var searchHadFocus by remember { mutableStateOf(false) }
            var messageHadFocus by remember { mutableStateOf(false) }

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
                        text = "Text input compose",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text("Search") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                FixtureLog.event("text_compose", "submit", "field_search", searchText)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("field_search")
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    searchHadFocus = true
                                } else if (searchHadFocus) {
                                    if (searchText.isNotEmpty()) {
                                        FixtureLog.event(
                                            "text_compose",
                                            "text",
                                            "field_search",
                                            searchText
                                        )
                                    }
                                }
                            }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    TextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        label = { Text("Message") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("field_message")
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    messageHadFocus = true
                                } else if (messageHadFocus) {
                                    if (messageText.isNotEmpty()) {
                                        FixtureLog.event(
                                            "text_compose",
                                            "text",
                                            "field_message",
                                            messageText
                                        )
                                    }
                                }
                            }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            FixtureLog.event("text_compose", "submit", "field_message", messageText)
                            messageText = ""
                        },
                        modifier = Modifier.testTag("btn_send")
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}
