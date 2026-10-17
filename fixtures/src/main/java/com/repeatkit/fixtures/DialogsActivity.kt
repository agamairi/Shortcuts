package com.repeatkit.fixtures

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.bottomsheet.BottomSheetDialog

class DialogsActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialogs)

        FixtureLog.event("dialogs", "open", "dialogs")

        val btnDialog = findViewById<Button>(R.id.btn_dialog)
        val btnSheet = findViewById<Button>(R.id.btn_sheet)
        val btnMenu = findViewById<Button>(R.id.btn_menu)

        btnDialog.setOnClickListener {
            FixtureLog.event("dialogs", "tap", "btn_dialog")
            AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setPositiveButton("OK") { _, _ ->
                    FixtureLog.event("dialogs", "select", "opt_ok")
                }
                .setNegativeButton("Cancel") { _, _ ->
                    FixtureLog.event("dialogs", "select", "opt_cancel")
                }
                .show()
        }

        btnSheet.setOnClickListener {
            FixtureLog.event("dialogs", "tap", "btn_sheet")
            val bottomSheet = BottomSheetDialog(this)
            val sheetView = layoutInflater.inflate(R.layout.layout_bottom_sheet, null)
            sheetView.findViewById<TextView>(R.id.opt_share).setOnClickListener {
                FixtureLog.event("dialogs", "select", "opt_share")
                bottomSheet.dismiss()
            }
            sheetView.findViewById<TextView>(R.id.opt_copy).setOnClickListener {
                FixtureLog.event("dialogs", "select", "opt_copy")
                bottomSheet.dismiss()
            }
            sheetView.findViewById<TextView>(R.id.opt_delete).setOnClickListener {
                FixtureLog.event("dialogs", "select", "opt_delete")
                bottomSheet.dismiss()
            }
            bottomSheet.setContentView(sheetView)
            bottomSheet.show()
        }

        btnMenu.setOnClickListener {
            FixtureLog.event("dialogs", "tap", "btn_menu")
            val popup = PopupMenu(this, btnMenu)
            popup.menuInflater.inflate(R.menu.menu_popup, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.opt_rename -> {
                        FixtureLog.event("dialogs", "select", "opt_rename")
                        true
                    }
                    R.id.opt_archive -> {
                        FixtureLog.event("dialogs", "select", "opt_archive")
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
}
