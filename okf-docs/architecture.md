---
title: System Architecture & Stack
type: architecture
author: Agent
tags: [architecture, android, mvvm, on-device-ai]
---

# System Architecture & Stack

## Overview
This Android application acts as a custom automation and widget builder. It allows users to create automation flows both manually via a UI and automatically via natural language using an on-device AI model (`function-gemma`).

## Technology Stack
- **Language**: Kotlin
- **UI Toolkit**: Jetpack Compose
- **Architecture Pattern**: MVVM (Model-View-ViewModel) + Clean Architecture
- **Dependency Injection**: Hilt (optional) / Manual
- **Local Database**: Room (for saving automations and widgets)
- **Widgets**: Jetpack Glance (AppWidgets built with Compose)
- **On-Device Inference**: Google LiteRT / MediaPipe Tasks API

## Key Components

### 1. Automation Builder UI
A Jetpack Compose screen where users can visually construct a list of actions (e.g., Turn on WiFi, Open App).

### 2. AI Prompt Builder UI
A screen where users can describe what they want to automate. The app invokes the downloaded `function-gemma` model to parse the request and generate a JSON schema representing the automation.

### 3. Action Executor Service
A background service (or Accessibility Service) that processes the JSON automation schema and sequentially triggers the system actions. It handles permissions and system settings.

### 4. Widget Provider
Uses Jetpack Glance to map saved automations to Home Screen widgets. Clicking a widget triggers the Action Executor Service.
