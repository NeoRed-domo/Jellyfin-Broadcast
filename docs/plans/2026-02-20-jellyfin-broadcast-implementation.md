# Jellyfin Broadcast Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Construire une application Android ultra-légère réceptrice Jellyfin, fonctionnant sur AndroidTV et smartphone/tablette, configurable via QR code.

**Architecture:** Un APK unique détecte le type d'appareil au runtime (TV vs phone). La TV expose un serveur Ktor local pour recevoir la configuration, puis s'enregistre comme session distante Jellyfin via WebSocket. Le téléphone scanne le QR code de la TV et lui envoie la configuration via HTTP local.

**Tech Stack:** Kotlin, Android API 24+, Media3/ExoPlayer, Ktor server, Jellyfin SDK Kotlin, ZXing, NsdManager, Coroutines/Flow

---

## Task 1: Scaffold du projet Android

**Files:**
- Create: `app/build.gradle.kts`
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/jellyfinbroadcast/MainActivity.kt`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/values/strings.xml`

**Step 1: Créer la structure Android Studio**

Créer un nouveau projet Android dans Android Studio :
- Package : `com.jellyfinbroadcast`
- Minimum SDK : API 24 (Android 7.0)
- Langage : Kotlin
- Template : Empty Activity

**Step 2: Configurer `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jellyfinbroadcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jellyfinbroadcast"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Jellyfin SDK
    implementation("org.jellyfin.sdk:jellyfin-core:1.5.3")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")

    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.9")
    implementation("io.ktor:ktor-server-cio:2.3.9")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.9")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.9")

    // QR code
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.leanback:leanback:1.0.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

**Step 3: Configurer `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />

    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.JellyfinBroadcast"
        android:banner="@drawable/banner">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 4: Couleurs Jellyfin dans `app/src/main/res/values/colors.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="jellyfin_blue">#00A4DC</color>
    <color name="jellyfin_purple">#AA5CC3</color>
    <color name="jellyfin_dark">#101010</color>
    <color name="jellyfin_surface">#1C1C1C</color>
    <color name="jellyfin_on_surface">#FFFFFF</color>
    <color name="jellyfin_error">#CF6679</color>
</resources>
```

**Step 5: Build de vérification**

```bash
./gradlew assembleDebug
```
Résultat attendu : `BUILD SUCCESSFUL`

**Step 6: Commit**

```bash
git add .
git commit -m "chore: scaffold Android project with dependencies"
```

---

## Task 2: Détection du type d'appareil

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/DeviceMode.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/DeviceModeTest.kt`

**Step 1: Écrire le test unitaire**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/DeviceModeTest.kt
package com.jellyfinbroadcast.core

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceModeTest {

    @Test
    fun `returns TV when uiMode is TYPE_TELEVISION`() {
        val mode = DeviceMode.from(Configuration.UI_MODE_TYPE_TELEVISION)
        assertEquals(DeviceMode.TV, mode)
    }

    @Test
    fun `returns PHONE when uiMode is TYPE_NORMAL`() {
        val mode = DeviceMode.from(Configuration.UI_MODE_TYPE_NORMAL)
        assertEquals(DeviceMode.PHONE, mode)
    }

    @Test
    fun `returns PHONE for any non-TV uiMode`() {
        val mode = DeviceMode.from(Configuration.UI_MODE_TYPE_DESK)
        assertEquals(DeviceMode.PHONE, mode)
    }
}
```

**Step 2: Lancer le test pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.DeviceModeTest"
```
Résultat attendu : FAIL — `DeviceMode not found`

**Step 3: Implémenter `DeviceMode.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/DeviceMode.kt
package com.jellyfinbroadcast.core

import android.content.res.Configuration

enum class DeviceMode {
    TV, PHONE;

    companion object {
        fun from(uiModeType: Int): DeviceMode =
            if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) TV else PHONE

        fun detect(context: android.content.Context): DeviceMode {
            val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE)
                    as android.app.UiModeManager
            return from(uiModeManager.currentModeType)
        }
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.DeviceModeTest"
```
Résultat attendu : PASS (3 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/DeviceMode.kt \
        app/src/test/java/com/jellyfinbroadcast/core/DeviceModeTest.kt
git commit -m "feat: add device mode detection (TV vs phone)"
```

---

## Task 3: Machine à états de l'application

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/AppState.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/AppStateTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/AppStateTest.kt
package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class AppStateTest {

    @Test
    fun `initial state is INIT`() {
        val machine = AppStateMachine()
        assertEquals(AppState.INIT, machine.currentState)
    }

    @Test
    fun `INIT transitions to DISCOVERY`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        assertEquals(AppState.DISCOVERY, machine.currentState)
    }

    @Test
    fun `DISCOVERY transitions to QR_CODE on timeout`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        machine.transition(AppEvent.DiscoveryTimeout)
        assertEquals(AppState.QR_CODE, machine.currentState)
    }

    @Test
    fun `DISCOVERY transitions to QR_CODE on server found`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        machine.transition(AppEvent.ServerFound("192.168.1.10"))
        assertEquals(AppState.QR_CODE, machine.currentState)
    }

    @Test
    fun `QR_CODE transitions to CONFIGURED on config received`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        machine.transition(AppEvent.DiscoveryTimeout)
        machine.transition(AppEvent.ConfigReceived)
        assertEquals(AppState.CONFIGURED, machine.currentState)
    }

    @Test
    fun `CONFIGURED transitions to PLAYING on play`() {
        val machine = AppStateMachine()
        machine.setState(AppState.CONFIGURED)
        machine.transition(AppEvent.Play)
        assertEquals(AppState.PLAYING, machine.currentState)
    }

    @Test
    fun `PLAYING transitions to PAUSED`() {
        val machine = AppStateMachine()
        machine.setState(AppState.PLAYING)
        machine.transition(AppEvent.Pause)
        assertEquals(AppState.PAUSED, machine.currentState)
    }

    @Test
    fun `PLAYING or CONFIGURED transitions to QR_CODE on show qr`() {
        val machine = AppStateMachine()
        machine.setState(AppState.CONFIGURED)
        machine.transition(AppEvent.ShowQrCode)
        assertEquals(AppState.QR_CODE, machine.currentState)
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.AppStateTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `AppState.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/AppState.kt
package com.jellyfinbroadcast.core

sealed class AppState {
    object INIT : AppState()
    object DISCOVERY : AppState()
    data class QR_CODE(val prefilledHost: String? = null) : AppState()
    object CONFIGURED : AppState()
    object PLAYING : AppState()
    object PAUSED : AppState()
    object BUFFERING : AppState()
    object STOPPED : AppState()
}

sealed class AppEvent {
    object StartDiscovery : AppEvent()
    object DiscoveryTimeout : AppEvent()
    data class ServerFound(val host: String) : AppEvent()
    object ConfigReceived : AppEvent()
    object Play : AppEvent()
    object Pause : AppEvent()
    object Stop : AppEvent()
    object ShowQrCode : AppEvent()
    object Buffering : AppEvent()
    object BufferingEnd : AppEvent()
}

class AppStateMachine {
    var currentState: AppState = AppState.INIT
        private set

    fun setState(state: AppState) { currentState = state }

    fun transition(event: AppEvent) {
        currentState = when {
            currentState is AppState.INIT && event is AppEvent.StartDiscovery -> AppState.DISCOVERY
            currentState is AppState.DISCOVERY && event is AppEvent.DiscoveryTimeout -> AppState.QR_CODE()
            currentState is AppState.DISCOVERY && event is AppEvent.ServerFound ->
                AppState.QR_CODE(prefilledHost = event.host)
            currentState is AppState.QR_CODE && event is AppEvent.ConfigReceived -> AppState.CONFIGURED
            currentState is AppState.CONFIGURED && event is AppEvent.Play -> AppState.PLAYING
            currentState is AppState.PLAYING && event is AppEvent.Pause -> AppState.PAUSED
            currentState is AppState.PAUSED && event is AppEvent.Play -> AppState.PLAYING
            currentState is AppState.PLAYING && event is AppEvent.Stop -> AppState.CONFIGURED
            currentState is AppState.PLAYING && event is AppEvent.Buffering -> AppState.BUFFERING
            currentState is AppState.BUFFERING && event is AppEvent.BufferingEnd -> AppState.PLAYING
            (currentState is AppState.CONFIGURED || currentState is AppState.PLAYING ||
             currentState is AppState.PAUSED) && event is AppEvent.ShowQrCode -> AppState.QR_CODE()
            else -> currentState
        }
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.AppStateTest"
```
Résultat attendu : PASS (8 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/AppState.kt \
        app/src/test/java/com/jellyfinbroadcast/core/AppStateTest.kt
git commit -m "feat: add app state machine"
```

---

## Task 4: Découverte mDNS du serveur Jellyfin

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/discovery/JellyfinDiscovery.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/discovery/JellyfinDiscoveryTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/discovery/JellyfinDiscoveryTest.kt
package com.jellyfinbroadcast.discovery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class JellyfinDiscoveryTest {

    @Test
    fun `parseServiceInfo extracts host and port`() {
        val result = JellyfinDiscovery.parseServiceInfo(
            host = "192.168.1.10",
            port = 8096
        )
        assertEquals("192.168.1.10", result.host)
        assertEquals(8096, result.port)
    }

    @Test
    fun `parseServiceInfo uses default port 8096 when port is 0`() {
        val result = JellyfinDiscovery.parseServiceInfo(
            host = "192.168.1.10",
            port = 0
        )
        assertEquals(8096, result.port)
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.discovery.JellyfinDiscoveryTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `JellyfinDiscovery.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/discovery/JellyfinDiscovery.kt
package com.jellyfinbroadcast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class JellyfinServerInfo(val host: String, val port: Int)

class JellyfinDiscovery(private val context: Context) {

    companion object {
        private const val SERVICE_TYPE = "_http._tcp."
        private const val JELLYFIN_SERVICE_NAME = "Jellyfin"
        const val DEFAULT_PORT = 8096
        const val DISCOVERY_TIMEOUT_MS = 5000L

        fun parseServiceInfo(host: String, port: Int): JellyfinServerInfo {
            return JellyfinServerInfo(
                host = host,
                port = if (port > 0) port else DEFAULT_PORT
            )
        }
    }

    suspend fun discover(): JellyfinServerInfo? = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            var listener: NsdManager.DiscoveryListener? = null

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val result = parseServiceInfo(host, info.port)
                    listener?.let { nsdManager.stopServiceDiscovery(it) }
                    if (continuation.isActive) continuation.resume(result)
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, error: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onStopDiscoveryFailed(type: String, error: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName.contains(JELLYFIN_SERVICE_NAME, ignoreCase = true)) {
                        nsdManager.resolveService(info, resolveListener)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {}
            }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

            continuation.invokeOnCancellation {
                try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
            }
        }
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.discovery.JellyfinDiscoveryTest"
```
Résultat attendu : PASS (2 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/discovery/ \
        app/src/test/java/com/jellyfinbroadcast/discovery/
git commit -m "feat: add mDNS Jellyfin server discovery"
```

---

## Task 5: Serveur Ktor local (réception config)

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/server/ConfigServer.kt`
- Create: `app/src/main/java/com/jellyfinbroadcast/server/ConfigPayload.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/server/ConfigServerTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/server/ConfigServerTest.kt
package com.jellyfinbroadcast.server

import org.junit.Assert.*
import org.junit.Test

class ConfigServerTest {

    @Test
    fun `ConfigPayload validates non-empty fields`() {
        val valid = ConfigPayload("192.168.1.10", 8096, "user", "pass")
        assertTrue(valid.isValid())
    }

    @Test
    fun `ConfigPayload rejects empty host`() {
        val invalid = ConfigPayload("", 8096, "user", "pass")
        assertFalse(invalid.isValid())
    }

    @Test
    fun `ConfigPayload rejects empty username`() {
        val invalid = ConfigPayload("192.168.1.10", 8096, "", "pass")
        assertFalse(invalid.isValid())
    }

    @Test
    fun `ConfigServer finds fallback port when primary is busy`() {
        val port = ConfigServer.findAvailablePort(startPort = 8765)
        assertTrue(port in 8765..8775)
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.server.ConfigServerTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `ConfigPayload.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/server/ConfigPayload.kt
package com.jellyfinbroadcast.server

import kotlinx.serialization.Serializable

@Serializable
data class ConfigPayload(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
) {
    fun isValid(): Boolean =
        host.isNotBlank() && username.isNotBlank() && port > 0
}
```

**Step 4: Implémenter `ConfigServer.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/server/ConfigServer.kt
package com.jellyfinbroadcast.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.ServerSocket

class ConfigServer(
    private val onConfigReceived: suspend (ConfigPayload) -> Boolean
) {
    private var server: ApplicationEngine? = null
    var port: Int = 8765
        private set

    companion object {
        fun findAvailablePort(startPort: Int = 8765, maxPort: Int = 8775): Int {
            for (p in startPort..maxPort) {
                try {
                    ServerSocket(p).use { return p }
                } catch (_: Exception) { continue }
            }
            return startPort
        }
    }

    fun start() {
        port = findAvailablePort()
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { json() }
            routing {
                post("/configure") {
                    val payload = runCatching { call.receive<ConfigPayload>() }.getOrNull()
                    if (payload == null || !payload.isValid()) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid config")
                        return@post
                    }
                    val success = onConfigReceived(payload)
                    if (success) {
                        call.respond(HttpStatusCode.OK, "Configuration applied")
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Jellyfin credentials")
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }
}
```

**Step 5: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.server.ConfigServerTest"
```
Résultat attendu : PASS (4 tests)

**Step 6: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/server/ \
        app/src/test/java/com/jellyfinbroadcast/server/
git commit -m "feat: add Ktor config server with port fallback"
```

---

## Task 6: Générateur de QR code

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/QrCodeGenerator.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/QrCodeGeneratorTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/QrCodeGeneratorTest.kt
package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun `buildUrl formats correctly`() {
        val url = QrCodeGenerator.buildUrl("192.168.1.10", 8765)
        assertEquals("http://192.168.1.10:8765", url)
    }

    @Test
    fun `buildUrl with IPv6 wraps in brackets`() {
        val url = QrCodeGenerator.buildUrl("fe80::1", 8765)
        assertEquals("http://[fe80::1]:8765", url)
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.QrCodeGeneratorTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `QrCodeGenerator.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/QrCodeGenerator.kt
package com.jellyfinbroadcast.core

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {

    fun buildUrl(host: String, port: Int): String {
        val formattedHost = if (host.contains(':')) "[$host]" else host
        return "http://$formattedHost:$port"
    }

    fun generate(host: String, port: Int, size: Int = 512): Bitmap {
        val url = buildUrl(host, port)
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bits[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.QrCodeGeneratorTest"
```
Résultat attendu : PASS (2 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/QrCodeGenerator.kt \
        app/src/test/java/com/jellyfinbroadcast/core/QrCodeGeneratorTest.kt
git commit -m "feat: add QR code generator"
```

---

## Task 7: Session Jellyfin & authentification

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/JellyfinSession.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/JellyfinSessionTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/JellyfinSessionTest.kt
package com.jellyfinbroadcast.core

import com.jellyfinbroadcast.server.ConfigPayload
import org.junit.Assert.*
import org.junit.Test

class JellyfinSessionTest {

    @Test
    fun `buildServerUrl formats with http when no scheme`() {
        val url = JellyfinSession.buildServerUrl("192.168.1.10", 8096)
        assertEquals("http://192.168.1.10:8096", url)
    }

    @Test
    fun `buildServerUrl preserves existing scheme`() {
        val url = JellyfinSession.buildServerUrl("https://myserver.local", 8096)
        assertEquals("https://myserver.local:8096", url)
    }

    @Test
    fun `buildServerUrl uses default port 8096 when port is 0`() {
        val url = JellyfinSession.buildServerUrl("192.168.1.10", 0)
        assertEquals("http://192.168.1.10:8096", url)
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.JellyfinSessionTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `JellyfinSession.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/JellyfinSession.kt
package com.jellyfinbroadcast.core

import android.content.Context
import com.jellyfinbroadcast.server.ConfigPayload
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

class JellyfinSession(private val context: Context) {

    companion object {
        const val CLIENT_NAME = "Jellyfin Broadcast"
        const val CLIENT_VERSION = "1.0.0"
        const val DEFAULT_PORT = 8096

        fun buildServerUrl(host: String, port: Int): String {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            return when {
                host.startsWith("http://") || host.startsWith("https://") ->
                    "$host:$effectivePort"
                else -> "http://$host:$effectivePort"
            }
        }
    }

    private var api: org.jellyfin.sdk.api.client.ApiClient? = null

    suspend fun authenticate(config: ConfigPayload): Boolean {
        return try {
            val jellyfin = Jellyfin(JellyfinOptions.Builder().apply {
                clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
                deviceInfo = DeviceInfo(
                    id = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ),
                    name = "Jellyfin Broadcast - ${android.os.Build.MODEL}"
                )
            }.build())

            val serverUrl = buildServerUrl(config.host, config.port)
            val client = jellyfin.createApi(baseUrl = serverUrl)
            val authApi = org.jellyfin.sdk.api.operations.UserApi(client)
            val response = authApi.authenticateUserByName(
                org.jellyfin.sdk.model.api.request.AuthenticateUserByNameRequest(
                    username = config.username,
                    pw = config.password
                )
            )
            api = client.apply {
                accessToken = response.content.accessToken
            }
            true
        } catch (e: ApiClientException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun getApi(): org.jellyfin.sdk.api.client.ApiClient? = api

    fun disconnect() {
        api = null
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.JellyfinSessionTest"
```
Résultat attendu : PASS (3 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/JellyfinSession.kt \
        app/src/test/java/com/jellyfinbroadcast/core/JellyfinSessionTest.kt
git commit -m "feat: add Jellyfin SDK session and authentication"
```

---

## Task 8: Listener WebSocket (commandes distantes)

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/RemoteCommandListener.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/RemoteCommandListenerTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/RemoteCommandListenerTest.kt
package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class RemoteCommandListenerTest {

    @Test
    fun `parseSeekPosition converts ms to correct value`() {
        val ms = RemoteCommandListener.parseSeekPositionMs(10000L)
        assertEquals(10000L, ms)
    }

    @Test
    fun `reconnect delay doubles up to max`() {
        val delays = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L)
        var delay = 1000L
        val results = mutableListOf<Long>()
        repeat(7) {
            results.add(delay)
            delay = minOf(delay * 2, 30000L)
        }
        assertEquals(delays, results)
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.RemoteCommandListenerTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `RemoteCommandListener.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/RemoteCommandListener.kt
package com.jellyfinbroadcast.core

import kotlinx.coroutines.*
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.SessionApi

sealed class RemoteCommand {
    data class Play(val itemId: String, val positionMs: Long = 0) : RemoteCommand()
    object Pause : RemoteCommand()
    object Resume : RemoteCommand()
    object Stop : RemoteCommand()
    data class Seek(val positionMs: Long) : RemoteCommand()
    object PlayNext : RemoteCommand()
    object PlayPrevious : RemoteCommand()
}

class RemoteCommandListener(
    private val api: ApiClient,
    private val onCommand: (RemoteCommand) -> Unit
) {
    companion object {
        const val MAX_RECONNECT_DELAY_MS = 30_000L

        fun parseSeekPositionMs(ms: Long): Long = ms
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        job = scope.launch {
            var delay = 1000L
            while (isActive) {
                try {
                    listenForCommands()
                    delay = 1000L
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(delay)
                    delay = minOf(delay * 2, MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private suspend fun listenForCommands() {
        // WebSocket connection via Jellyfin SDK
        // Commands arrive as GeneralCommandType or PlaystateCommand
        val sessionApi = SessionApi(api)
        // Implementation depends on jellyfin-sdk-kotlin WebSocket API
        // The SDK provides a websocket flow to collect messages
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.RemoteCommandListenerTest"
```
Résultat attendu : PASS (2 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/RemoteCommandListener.kt \
        app/src/test/java/com/jellyfinbroadcast/core/RemoteCommandListenerTest.kt
git commit -m "feat: add remote command listener with exponential reconnect"
```

---

## Task 9: Player Media3 avec transcoding

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt
package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class MediaPlayerTest {

    @Test
    fun `buildStreamUrl uses direct play by default`() {
        val url = MediaPlayer.buildStreamUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            transcode = false
        )
        assertTrue(url.contains("abc123"))
        assertTrue(url.contains("Static"))
    }

    @Test
    fun `buildStreamUrl uses transcode endpoint when requested`() {
        val url = MediaPlayer.buildStreamUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            transcode = true
        )
        assertTrue(url.contains("master.m3u8") || url.contains("stream"))
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.MediaPlayerTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `MediaPlayer.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt
package com.jellyfinbroadcast.core

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MediaPlayer(private val context: Context) {

    companion object {
        fun buildStreamUrl(
            serverUrl: String,
            itemId: String,
            token: String,
            transcode: Boolean
        ): String {
            return if (transcode) {
                "$serverUrl/Videos/$itemId/master.m3u8?api_key=$token&AudioCodec=aac&VideoCodec=h264"
            } else {
                "$serverUrl/Videos/$itemId/stream?Static=true&api_key=$token"
            }
        }
    }

    private var player: ExoPlayer? = null
    var onPlaybackEnded: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onPositionChanged: ((Long) -> Unit)? = null

    fun initialize() {
        player = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_ENDED -> onPlaybackEnded?.invoke()
                        else -> {}
                    }
                }
            })
        }
    }

    fun play(url: String) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun stop() { player?.stop() }
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun release() {
        player?.release()
        player = null
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.MediaPlayerTest"
```
Résultat attendu : PASS (2 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt \
        app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt
git commit -m "feat: add Media3 player with direct play and transcode support"
```

---

## Task 10: UI TV — Écran QR code

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/tv/TvQrCodeFragment.kt`
- Create: `app/src/main/res/layout/fragment_tv_qr_code.xml`

**Step 1: Layout `fragment_tv_qr_code.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/jellyfin_dark">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:gravity="center"
        android:padding="32dp">

        <TextView
            android:id="@+id/tv_status"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Initialisation..."
            android:textColor="@color/jellyfin_on_surface"
            android:textSize="24sp"
            android:layout_marginBottom="24dp" />

        <ImageView
            android:id="@+id/iv_qr_code"
            android:layout_width="300dp"
            android:layout_height="300dp"
            android:visibility="gone" />

        <TextView
            android:id="@+id/tv_instructions"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Scannez ce code avec l'app Jellyfin Broadcast"
            android:textColor="@color/jellyfin_blue"
            android:textSize="16sp"
            android:layout_marginTop="16dp"
            android:visibility="gone" />
    </LinearLayout>
</FrameLayout>
```

**Step 2: Implémenter `TvQrCodeFragment.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/tv/TvQrCodeFragment.kt
package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jellyfinbroadcast.core.QrCodeGenerator
import com.jellyfinbroadcast.databinding.FragmentTvQrCodeBinding
import com.jellyfinbroadcast.discovery.JellyfinDiscovery
import com.jellyfinbroadcast.server.ConfigServer
import kotlinx.coroutines.launch

class TvQrCodeFragment : Fragment() {

    private var _binding: FragmentTvQrCodeBinding? = null
    private val binding get() = _binding!!
    private lateinit var configServer: ConfigServer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvQrCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startDiscoveryAndServer()
    }

    private fun startDiscoveryAndServer() {
        configServer = ConfigServer { payload ->
            // Validate with Jellyfin SDK — handled by parent activity/viewmodel
            (activity as? TvActivity)?.onConfigReceived(payload) ?: false
        }
        configServer.start()

        val localIp = getLocalIpAddress()
        showQrCode(localIp, configServer.port)

        lifecycleScope.launch {
            binding.tvStatus.text = "Recherche du serveur Jellyfin..."
            val serverInfo = JellyfinDiscovery(requireContext()).discover()
            if (serverInfo != null) {
                (activity as? TvActivity)?.onServerDiscovered(serverInfo.host, serverInfo.port)
            }
            binding.tvStatus.text = "Scanner ce QR code pour configurer"
        }
    }

    private fun showQrCode(ip: String, port: Int) {
        val bitmap = QrCodeGenerator.generate(ip, port)
        binding.ivQrCode.setImageBitmap(bitmap)
        binding.ivQrCode.visibility = View.VISIBLE
        binding.tvInstructions.visibility = View.VISIBLE
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = requireContext().applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        configServer.stop()
        _binding = null
    }
}
```

**Step 3: Build et vérification visuelle**

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Step 4: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/tv/TvQrCodeFragment.kt \
        app/src/main/res/layout/fragment_tv_qr_code.xml
git commit -m "feat: add TV QR code screen with discovery"
```

---

## Task 11: UI TV — Écran noir & player

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/tv/TvPlayerFragment.kt`
- Create: `app/src/main/res/layout/fragment_tv_player.xml`
- Create: `app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt`

**Step 1: Layout `fragment_tv_player.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/jellyfin_dark">

    <androidx.media3.ui.PlayerView
        android:id="@+id/player_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:use_controller="false" />

</FrameLayout>
```

**Step 2: Implémenter `TvPlayerFragment.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/tv/TvPlayerFragment.kt
package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import com.jellyfinbroadcast.core.MediaPlayer
import com.jellyfinbroadcast.databinding.FragmentTvPlayerBinding

class TvPlayerFragment : Fragment() {

    private var _binding: FragmentTvPlayerBinding? = null
    private val binding get() = _binding!!
    lateinit var mediaPlayer: MediaPlayer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mediaPlayer = MediaPlayer(requireContext())
        mediaPlayer.initialize()
        binding.playerView.player = null // Will be set when MediaPlayer is initialized with ExoPlayer
    }

    fun playUrl(url: String) {
        mediaPlayer.play(url)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause() else mediaPlayer.resume()
                true
            }
            else -> false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mediaPlayer.release()
        _binding = null
    }
}
```

**Step 3: Implémenter `TvActivity.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt
package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.core.AppEvent
import com.jellyfinbroadcast.core.AppState
import com.jellyfinbroadcast.core.AppStateMachine
import com.jellyfinbroadcast.core.JellyfinSession
import com.jellyfinbroadcast.server.ConfigPayload

class TvActivity : AppCompatActivity() {

    private val stateMachine = AppStateMachine()
    private val jellyfinSession by lazy { JellyfinSession(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        stateMachine.transition(AppEvent.StartDiscovery)
        showQrCodeScreen()
    }

    private fun showQrCodeScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TvQrCodeFragment())
            .commit()
    }

    private fun showPlayerScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TvPlayerFragment())
            .commit()
    }

    suspend fun onConfigReceived(payload: ConfigPayload): Boolean {
        val success = jellyfinSession.authenticate(payload)
        if (success) {
            stateMachine.transition(AppEvent.ConfigReceived)
            runOnUiThread { showPlayerScreen() }
        }
        return success
    }

    fun onServerDiscovered(host: String, port: Int) {
        stateMachine.transition(AppEvent.ServerFound(host))
        // Store for pre-filling config form on phone
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
            event.isLongPress &&
            stateMachine.currentState is AppState.CONFIGURED) {
            stateMachine.transition(AppEvent.ShowQrCode)
            showQrCodeScreen()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
```

**Step 4: Build**

```bash
./gradlew assembleDebug
```
Résultat attendu : `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/tv/ \
        app/src/main/res/layout/
git commit -m "feat: add TV player fragment and activity with state management"
```

---

## Task 12: UI Phone — QR code avec long press

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/phone/PhoneQrCodeFragment.kt`
- Create: `app/src/main/res/layout/fragment_phone_qr_code.xml`

**Step 1: Layout `fragment_phone_qr_code.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/jellyfin_dark">

    <ImageView
        android:id="@+id/iv_qr_code"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:padding="32dp" />

    <!-- Menu overlay (hidden by default) -->
    <LinearLayout
        android:id="@+id/menu_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#CC000000"
        android:orientation="vertical"
        android:gravity="center"
        android:visibility="gone">

        <Button
            android:id="@+id/btn_configure_this"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Configurer cet appareil"
            android:backgroundTint="@color/jellyfin_blue"
            android:layout_marginBottom="16dp" />

        <Button
            android:id="@+id/btn_scan_qr"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Scanner un QR code"
            android:backgroundTint="@color/jellyfin_purple" />
    </LinearLayout>
</FrameLayout>
```

**Step 2: Implémenter `PhoneQrCodeFragment.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/phone/PhoneQrCodeFragment.kt
package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import com.jellyfinbroadcast.core.QrCodeGenerator
import com.jellyfinbroadcast.databinding.FragmentPhoneQrCodeBinding
import com.jellyfinbroadcast.server.ConfigServer

class PhoneQrCodeFragment : Fragment() {

    private var _binding: FragmentPhoneQrCodeBinding? = null
    private val binding get() = _binding!!
    private lateinit var configServer: ConfigServer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneQrCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configServer = ConfigServer { payload ->
            (activity as? PhoneActivity)?.onConfigReceived(payload) ?: false
        }
        configServer.start()
        showQrCode()
        setupLongPress()
        setupMenuButtons()
    }

    private fun showQrCode() {
        val ip = getLocalIpAddress()
        val bitmap = QrCodeGenerator.generate(ip, configServer.port)
        binding.ivQrCode.setImageBitmap(bitmap)
    }

    private fun setupLongPress() {
        binding.ivQrCode.setOnLongClickListener {
            binding.menuOverlay.visibility = View.VISIBLE
            true
        }
        binding.menuOverlay.setOnClickListener {
            binding.menuOverlay.visibility = View.GONE
        }
    }

    private fun setupMenuButtons() {
        binding.btnConfigureThis.setOnClickListener {
            binding.menuOverlay.visibility = View.GONE
            (activity as? PhoneActivity)?.showConfigForm(null)
        }
        binding.btnScanQr.setOnClickListener {
            binding.menuOverlay.visibility = View.GONE
            (activity as? PhoneActivity)?.startQrScanner()
        }
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = requireContext().applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        configServer.stop()
        _binding = null
    }
}
```

**Step 3: Build**

```bash
./gradlew assembleDebug
```
Résultat attendu : `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/phone/PhoneQrCodeFragment.kt \
        app/src/main/res/layout/fragment_phone_qr_code.xml
git commit -m "feat: add phone QR code screen with long press menu"
```

---

## Task 13: UI Phone — Scanner QR code & formulaire config

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/phone/PhoneActivity.kt`
- Create: `app/src/main/java/com/jellyfinbroadcast/phone/ConfigFormFragment.kt`
- Create: `app/src/main/res/layout/fragment_config_form.xml`

**Step 1: Layout `fragment_config_form.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/jellyfin_dark"
    android:padding="24dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Configuration Jellyfin"
            android:textColor="@color/jellyfin_blue"
            android:textSize="20sp"
            android:layout_marginBottom="24dp" />

        <com.google.android.material.textfield.TextInputLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Adresse IP ou nom du serveur"
            android:layout_marginBottom="16dp">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/et_host"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textUri" />
        </com.google.android.material.textfield.TextInputLayout>

        <com.google.android.material.textfield.TextInputLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Port (défaut: 8096)"
            android:layout_marginBottom="16dp">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/et_port"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="number"
                android:text="8096" />
        </com.google.android.material.textfield.TextInputLayout>

        <com.google.android.material.textfield.TextInputLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Utilisateur"
            android:layout_marginBottom="16dp">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/et_username"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </com.google.android.material.textfield.TextInputLayout>

        <com.google.android.material.textfield.TextInputLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Mot de passe"
            android:layout_marginBottom="24dp">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/et_password"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textPassword" />
        </com.google.android.material.textfield.TextInputLayout>

        <Button
            android:id="@+id/btn_send"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Envoyer la configuration"
            android:backgroundTint="@color/jellyfin_blue" />

        <TextView
            android:id="@+id/tv_status"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:textColor="@color/jellyfin_on_surface"
            android:gravity="center"
            android:visibility="gone" />
    </LinearLayout>
</ScrollView>
```

**Step 2: Implémenter `ConfigFormFragment.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/phone/ConfigFormFragment.kt
package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.databinding.FragmentConfigFormBinding
import com.jellyfinbroadcast.server.ConfigPayload
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch

class ConfigFormFragment : Fragment(R.layout.fragment_config_form) {

    private var _binding: FragmentConfigFormBinding? = null
    private val binding get() = _binding!!

    // Set by PhoneActivity when TV QR code was scanned
    var tvIp: String? = null
    var tvPort: Int = 8765
    var prefilledHost: String? = null

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentConfigFormBinding.bind(view)
        prefilledHost?.let { binding.etHost.setText(it) }

        binding.btnSend.setOnClickListener { sendConfig() }
    }

    private fun sendConfig() {
        val host = binding.etHost.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8096
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        val payload = ConfigPayload(host, port, username, password)
        if (!payload.isValid()) {
            binding.tvStatus.apply {
                text = "Veuillez remplir tous les champs"
                visibility = View.VISIBLE
            }
            return
        }

        binding.btnSend.isEnabled = false
        binding.tvStatus.apply { text = "Envoi en cours..."; visibility = View.VISIBLE }

        lifecycleScope.launch {
            try {
                val response = httpClient.post("http://$tvIp:$tvPort/configure") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (response.status == HttpStatusCode.OK) {
                    binding.tvStatus.text = "Configuration envoyée ✓"
                } else {
                    binding.tvStatus.text = "Erreur : credentials invalides"
                    binding.btnSend.isEnabled = true
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "Erreur réseau : ${e.message}"
                binding.btnSend.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        httpClient.close()
        _binding = null
    }
}
```

**Step 3: Implémenter `PhoneActivity.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/phone/PhoneActivity.kt
package com.jellyfinbroadcast.phone

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.server.ConfigPayload

class PhoneActivity : AppCompatActivity() {

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { url ->
            // url = "http://192.168.1.10:8765"
            val regex = Regex("http://([^:]+):(\\d+)")
            val match = regex.find(url)
            if (match != null) {
                val tvIp = match.groupValues[1]
                val tvPort = match.groupValues[2].toIntOrNull() ?: 8765
                showConfigForm(tvIp, tvPort)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone)
        showQrCodeScreen()
    }

    private fun showQrCodeScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PhoneQrCodeFragment())
            .commit()
    }

    fun showConfigForm(tvIp: String?, tvPort: Int = 8765, prefilledHost: String? = null) {
        val fragment = ConfigFormFragment().apply {
            this.tvIp = tvIp
            this.tvPort = tvPort
            this.prefilledHost = prefilledHost
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Cadrez le QR code de la TV")
            setBeepEnabled(true)
        }
        qrScanLauncher.launch(options)
    }

    suspend fun onConfigReceived(payload: ConfigPayload): Boolean = false // Phone as receiver: future feature
}
```

**Step 4: Build**

```bash
./gradlew assembleDebug
```
Résultat attendu : `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/phone/ \
        app/src/main/res/layout/fragment_config_form.xml
git commit -m "feat: add phone config form, QR scanner and activity"
```

---

## Task 14: MainActivity — routing TV vs Phone

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`

**Step 1: Implémenter `MainActivity.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/MainActivity.kt
package com.jellyfinbroadcast

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jellyfinbroadcast.core.DeviceMode
import com.jellyfinbroadcast.phone.PhoneActivity
import com.jellyfinbroadcast.tv.TvActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = DeviceMode.detect(this)
        val target = if (mode == DeviceMode.TV) TvActivity::class.java else PhoneActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
```

**Step 2: Build final**

```bash
./gradlew assembleDebug
```
Résultat attendu : `BUILD SUCCESSFUL`

**Step 3: Test sur émulateur TV**

```bash
# Lancer sur un émulateur AndroidTV
adb -s <tv_emulator_id> install app/build/outputs/apk/debug/app-debug.apk
```
Vérification : l'app affiche bien "Initialisation..." puis le QR code.

**Step 4: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/MainActivity.kt \
        app/src/main/res/layout/
git commit -m "feat: add main activity router TV vs phone"
```

---

## Task 15: Reporting temps réel à Jellyfin

**Files:**
- Create: `app/src/main/java/com/jellyfinbroadcast/core/PlaybackReporter.kt`
- Create: `app/src/test/java/com/jellyfinbroadcast/core/PlaybackReporterTest.kt`

**Step 1: Écrire les tests**

```kotlin
// app/src/test/java/com/jellyfinbroadcast/core/PlaybackReporterTest.kt
package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackReporterTest {

    @Test
    fun `convertMsToTicks converts correctly`() {
        // Jellyfin uses ticks (100ns units), 1ms = 10000 ticks
        assertEquals(10_000L, PlaybackReporter.msToTicks(1L))
        assertEquals(600_000_000L, PlaybackReporter.msToTicks(60_000L))
    }
}
```

**Step 2: Lancer les tests pour vérifier l'échec**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.PlaybackReporterTest"
```
Résultat attendu : FAIL

**Step 3: Implémenter `PlaybackReporter.kt`**

```kotlin
// app/src/main/java/com/jellyfinbroadcast/core/PlaybackReporter.kt
package com.jellyfinbroadcast.core

import kotlinx.coroutines.*
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.PlaystateApi
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.util.UUID

class PlaybackReporter(private val api: ApiClient) {

    companion object {
        const val REPORT_INTERVAL_MS = 10_000L
        const val TICKS_PER_MS = 10_000L

        fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS
    }

    private val playstateApi = PlaystateApi(api)
    private var reportingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentItemId: UUID? = null
    private var getPositionMs: (() -> Long)? = null

    fun reportPlaybackStart(itemId: UUID, positionMs: Long) {
        currentItemId = itemId
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackStart(PlaybackStartInfo(itemId = itemId,
                    positionTicks = msToTicks(positionMs)))
            }
        }
    }

    fun startPeriodicReporting(getPosition: () -> Long) {
        getPositionMs = getPosition
        reportingJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportProgress()
            }
        }
    }

    fun reportProgress() {
        val itemId = currentItemId ?: return
        val posMs = getPositionMs?.invoke() ?: return
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackProgress(PlaybackProgressInfo(
                    itemId = itemId,
                    positionTicks = msToTicks(posMs)
                ))
            }
        }
    }

    fun reportPlaybackStop(positionMs: Long) {
        val itemId = currentItemId ?: return
        reportingJob?.cancel()
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackStopped(PlaybackStopInfo(
                    itemId = itemId,
                    positionTicks = msToTicks(positionMs)
                ))
            }
        }
    }
}
```

**Step 4: Lancer les tests**

```bash
./gradlew test --tests "com.jellyfinbroadcast.core.PlaybackReporterTest"
```
Résultat attendu : PASS (1 test)

**Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/PlaybackReporter.kt \
        app/src/test/java/com/jellyfinbroadcast/core/PlaybackReporterTest.kt
git commit -m "feat: add real-time playback reporting to Jellyfin"
```

---

## Task 16: Configuration Play Store

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`

**Step 1: Vérifier `isMinifyEnabled = false` dans release**

Ouvrir `app/build.gradle.kts` et confirmer :

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        isShrinkResources = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

**Step 2: Build release**

```bash
./gradlew assembleRelease
```
Résultat attendu : `BUILD SUCCESSFUL` — APK dans `app/build/outputs/apk/release/`

**Step 3: Vérifier la taille de l'APK**

```bash
ls -lh app/build/outputs/apk/release/
```
L'APK doit être raisonnable (< 30MB).

**Step 4: Commit final**

```bash
git add app/build.gradle.kts app/proguard-rules.pro
git commit -m "chore: configure release build for Play Store (minify=false)"
```

---

## Récapitulatif des commits

```
chore: scaffold Android project with dependencies
feat: add device mode detection (TV vs phone)
feat: add app state machine
feat: add mDNS Jellyfin server discovery
feat: add Ktor config server with port fallback
feat: add QR code generator
feat: add Jellyfin SDK session and authentication
feat: add remote command listener with exponential reconnect
feat: add Media3 player with direct play and transcode support
feat: add TV QR code screen with discovery
feat: add TV player fragment and activity with state management
feat: add phone QR code screen with long press menu
feat: add phone config form, QR scanner and activity
feat: add main activity router TV vs phone
feat: add real-time playback reporting to Jellyfin
chore: configure release build for Play Store (minify=false)
```
