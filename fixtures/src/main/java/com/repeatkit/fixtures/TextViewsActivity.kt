package com.repeatkit.fixtures

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText

class TextViewsActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_views)

        FixtureLog.event("text_views", "open", "text_views")

        val editSearch = findViewById<EditText>(R.id.edit_search)
        val editMessage = findViewById<EditText>(R.id.edit_message)
        val btnSend = findViewById<Button>(R.id.btn_send)

        editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = editSearch.text.toString()
                FixtureLog.event("text_views", "submit", "edit_search", text)
                true
            } else {
                false
            }
        }

        editSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = editSearch.text.toString()
                if (text.isNotEmpty()) {
                    FixtureLog.event("text_views", "text", "edit_search", text)
                }
            }
        }

        editMessage.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = editMessage.text.toString()
                if (text.isNotEmpty()) {
                    FixtureLog.event("text_views", "text", "edit_message", text)
                }
            }
        }

        btnSend.setOnClickListener {
            val text = editMessage.text.toString()
            FixtureLog.event("text_views", "submit", "edit_message", text)
            editMessage.text.clear()
        }
    }
}
