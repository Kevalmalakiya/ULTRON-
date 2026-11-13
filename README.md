# ULTRON Android

A native Android interface for the ULTRON AI project.

## V1 features

- Kotlin and Jetpack Compose
- Dark metallic visual design
- Original 2D robotic avatar drawn with Compose Canvas
- Interactive local chat interface
- Local chat history using Android SharedPreferences
- Settings panel and clear-history action
- API-ready architecture boundary
- GitHub Actions debug APK build

## Current backend status

The current V1 is a UI preview.

It does not yet connect to Qwen, Google Colab, or a remote API.
Messages are stored locally and the interface explicitly reports
that Cloud API is not connected.

The FastAPI client will be added in a later version.
No credentials or private model files belong in this repository.

## Build information

- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.3.21
- Compose BOM: 2026.09.00
- Compile SDK: 36
- Minimum Android SDK: 26
- Java target: 17

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main`
and on manual workflow dispatch.

The output artifact is named `ULTRON-debug-apk`.

## Avatar and intellectual property

The avatar is original vector-style artwork authored for this application.
It uses broad science-fiction design elements such as dark metallic
armour, angular plating and red reactor accents.

It is not a reproduction of Marvel artwork or a Marvel character asset.
Uploaded reference images are not included in this public repository.
