import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Entra app registration client id for OneDrive integration. **The only place to fill it in** -- see
 * `docs/PlaningStages/onedrive-setup.md`. It drives `BuildConfig.MSAL_CLIENT_ID`, which generates the
 * MSAL config file at runtime (`res/raw` cannot interpolate).
 */
val msalClientId: String = (findProperty("msalClientId") as String?)
    ?.takeIf { it.isNotBlank() && !it.startsWith("REPLACE") }
    ?: "REPLACE-WITH-CLIENT-ID"

/**
 * Signature hash (base64 of the certificate SHA-1). The MSAL Android callback address is
 * `msauth://<package name>/<this value, URL-encoded>` -- not `msal<clientId>://auth`
 * (that is the MSAL.NET form, which is legacy on Android).
 *
 * By default it is computed right here from the debug keystore, so debug builds **need no manual
 * maintenance** of it; for release or a different signing key, override with `-PmsalSignatureHash=...`
 * or in gradle.properties. Note: this is an undeclared input read at configuration time, so after
 * swapping keystores the configuration cache may still use the old value; `--rerun-tasks` forces a refresh.
 */
fun debugKeystoreSignatureHash(): String? {
    val keystore = File(System.getProperty("user.home"), ".android/debug.keystore")
    if (!keystore.isFile) return null
    val certificate: Certificate? = try {
        val store = KeyStore.getInstance("JKS")
        keystore.inputStream().use { stream -> store.load(stream, "android".toCharArray()) }
        store.getCertificate("androiddebugkey")
    } catch (e: Exception) {
        null
    }
    if (certificate == null) return null
    val digest = MessageDigest.getInstance("SHA-1").digest(certificate.encoded)
    return Base64.getEncoder().encodeToString(digest)
}

val msalSignatureHash: String = (findProperty("msalSignatureHash") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: debugKeystoreSignatureHash()
    ?: "REPLACE-WITH-SIGNATURE-HASH"

/**
 * Release signing credentials (V1.0): read only from `local.properties` (gitignored); no key material is
 * ever left in the repository. If any single item is missing no signingConfig is created -- on other
 * machines/CI debug builds work as usual, while the release artifact is explicitly named
 * `app-release-unsigned.apk`, rather than being signed with an empty password into a defective build that
 * looks like a real package.
 *
 * `RELEASE_STORE_FILE` is resolved relative to the **repository root** (`rootProject.file` below),
 * matching the layout that keeps `keystore/` in the root directory. Note this is an undeclared input read
 * at configuration time: after editing local.properties the configuration cache may still use the old
 * value, and `--rerun-tasks` forces a refresh (the same trap as msalSignatureHash above).
 */
val releaseSigning: Map<String, String> = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { file -> file.isFile }?.inputStream()?.use { stream ->
        props.load(stream)
    }
    listOf(
        "RELEASE_STORE_FILE",
        "RELEASE_STORE_PASSWORD",
        "RELEASE_KEY_ALIAS",
        "RELEASE_KEY_PASSWORD"
    ).associateWith { key -> props.getProperty(key).orEmpty() }
}
val releaseSigningReady: Boolean = releaseSigning.values.all { it.isNotBlank() }

android {
    namespace = "com.yangz3720.simplywork"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yangz3720.simplywork"
        minSdk = 34
        targetSdk = 35
        // V1.1: fixes the 5 UX issues of V1.0 (dialog shape, API key masking, context window, auto-approval).
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHash\"")
        // The manifest's BrowserTabActivity path uses the **unencoded** hash (and must carry a leading `/`);
        // only the config file's redirect_uri uses the URL-encoded one. Both take their value from the same source.
        manifestPlaceholders["msalSignatureHash"] = msalSignatureHash
    }

    signingConfigs {
        // Created only when the credentials are complete: otherwise release degrades to an unsigned artifact (named with -unsigned, not silent).
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("RELEASE_STORE_FILE"))
                storePassword = releaseSigning.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // V1.0 trimming: R8 code shrinking + resource shrinking. The keep rules of third-party libraries
            // live in app/proguard-rules.pro, where each entry notes which library it was added for -- this
            // code path only exposes breakage at runtime, so every rule change needs an on-device run.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Icons (official Compose family artifact managed by the Compose BOM; icons-core is already cached locally as a transitive dependency)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // KotlinX
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // AndroidX JavaScriptEngine
    implementation(libs.androidx.javascriptengine)

    // MSAL (OneDrive personal account OAuth 2.0 sign-in)
    implementation(libs.msal)

    // Markdown rendering (a dependency exception authorized by the user: replacing a hand-written parser with the official library)
    implementation(libs.mikepenz.markdown.renderer)
    implementation(libs.mikepenz.markdown.renderer.m3)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)

    // Android Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
