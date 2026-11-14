import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app's version is HOMEFLIX's version (package.json in the repository root).
val repoRoot: File = rootProject.projectDir.parentFile
val appVersion = (JsonSlurper().parse(File(repoRoot, "package.json")) as Map<*, *>)["version"] as String
val versionParts = appVersion.split(".").map { it.toInt() }

// Native inputs made by scripts/fetch-native.sh: the Node.js runtime and ffmpeg (not in git).
val nativeDir = rootProject.layout.buildDirectory.dir("native")

// Release signing comes from environment variables (GitHub secrets); without them the build is
// signed with the debug key, which is fine for testing.
val keystorePath: String? = System.getenv("HOMEFLIX_KEYSTORE")

android {
    namespace = "io.github.kprakz.homeflix"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.kprakz.homeflix"
        minSdk = 29 // Android 10: the phone-temperature API the heat watcher uses
        targetSdk = 35
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersion
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("HOMEFLIX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOMEFLIX_KEY_ALIAS")
                keyPassword = System.getenv("HOMEFLIX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // ffmpeg and ffprobe ship as "lib*.so" so Android unpacks them to a folder it lets apps run
    // programs from; that needs the old (extracted) way of packaging native libraries.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/nodeAssets"))
    sourceSets["main"].jniLibs.srcDir(nativeDir.map { it.dir("jniLibs") })
}

// The phone runs the very same server and web player as the desktop app: copy them in at build time.
val copyNodeAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/nodeAssets/nodejs-project"))
    from(File(repoRoot, "server.js"))
    from(File(repoRoot, "android/node/main.js"))
    from(File(repoRoot, "public")) { into("public") }
    from(File(repoRoot, "node_modules/hls.js/dist/hls.min.js")) { into("node_modules/hls.js/dist") }
    doLast {
        // Tells the app to unpack a fresh copy when the server changes.
        File(destinationDir, "VERSION").writeText("$appVersion ${System.currentTimeMillis()}")
    }
}
tasks.named("preBuild") { dependsOn(copyNodeAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
