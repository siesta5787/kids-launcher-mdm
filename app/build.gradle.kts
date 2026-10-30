import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GitCommitValueSource : ValueSource<String, ValueSourceParameters.None> {

    @Inject
    abstract fun getExecOperations(): ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val action = object : Action<ExecSpec> {
            override fun execute(t: ExecSpec) {
                t.commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
                t.standardOutput = output
            }
        }
        getExecOperations().exec(action)
        return String(output.toByteArray(), Charset.defaultCharset()).trim()
    }
}

val gitCommitProvider = providers.of(GitCommitValueSource::class) {}
val gitCommit = gitCommitProvider.get()

android {
    namespace = "com.kidslauncher.mdm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kidslauncher.mdm"
        minSdk = 34
        targetSdk = 36
        versionCode = 126
        versionName = "0.52.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        generateLocaleConfig = true
    }

    // Shipped builds are the release variant, signed with the shared Kids MDM release key (same
    // key as kids-mdm-im and kids-mdm-browser - their journal ContentProviders are
    // signature-permission-gated). CI provides it via RELEASE_KEYSTORE_* env vars; without them
    // (local builds, PR builds) the release APK is simply left unsigned.
    signingConfigs {
        System.getenv("RELEASE_KEYSTORE_PATH")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Off deliberately: R8 shrinking can strip gomobile's tsnet glue classes, which are
            // only ever reached from native code via JNI - a crash there on a pinned Device Owner
            // launcher has no fallback Home app to land in. Keeps release behavior identical to
            // the debug builds every feature was live-tested on.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
    }

    defaultConfig {
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = false
        dataBinding = true
        viewBinding = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes.addAll(
            listOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE-notice.md"
            )
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Built by CI (see .github/workflows/android.yml's "Build tsnet.aar with
    // gomobile" step, and mobile/go.mod) - not checked in, since it's a
    // multi-hundred-MB Go-toolchain build artifact. Gives the launcher its
    // own embeddable tailnet connection - see CLAUDE.md.
    implementation(files("libs/tsnet.aar"))
    // IP/UDP packet parsing+construction (with automatic checksum/length
    // correction) and DNS message parsing, for KidVpnService's local packet
    // filter - same libraries (and versions, for pcap4j) DNS66 uses for this
    // exact pattern on Android, confirmed via its own build.gradle rather
    // than assumed. MIT (pcap4j) and BSD-3-Clause (dnsjava), both compatible
    // with this repo's GPLv3.
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    implementation("dnsjava:dnsjava:3.6.5")
    // In-app "Scan setup QR" flow (SettingsFragmentLauncher) - ZXing, not Google's ML Kit, to
    // match this project's existing avoid-Google/Play-Services-dependencies pattern (embedded
    // tsnet over the standalone Tailscale app, UnifiedPush over FCM, etc.). Zero GMS footprint.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.squareup.retrofit)
    implementation(libs.jakewharton.retrofit2.kotlinx.serialization.converter)
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.sse)
    implementation(libs.jonahbauer.android.preference.annotations)
    annotationProcessor(libs.jonahbauer.android.preference.annotations)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
