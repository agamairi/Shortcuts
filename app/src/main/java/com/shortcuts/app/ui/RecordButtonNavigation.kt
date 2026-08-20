package com.shortcuts.app.ui

const val RECORDER_ROUTE = "recorder_screen"

/** The dashboard record action always opens the recorder; service readiness is handled there. */
fun recordButtonDestination(): String = RECORDER_ROUTE

/** Kept pure so the record-button contract can be verified for either service state. */
fun recordButtonDestination(@Suppress("UNUSED_PARAMETER") serviceActive: Boolean): String = RECORDER_ROUTE
