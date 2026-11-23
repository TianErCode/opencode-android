import java.io.File
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val repoRoot = rootProject.projectDir
val generatedRuntimeAssets = rootProject.layout.buildDirectory.dir("generated/runtime-assets")
val generatedRuntimeJni = rootProject.layout.buildDirectory.dir("generated/runtime-jni")




val pythonCommand =
    listOf(
        providers.gradleProperty("opencode.python").orNull,
        providers.gradleProperty("OPENCODE_PYTHON").orNull,
        System.getenv("OPENCODE_PYTHON"),
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
    ).firstOrNull { !it.isNullOrBlank() } ?: "python3"

val prepareOpenCodeRuntimeAssets =
    tasks.register<Exec>("prepareOpenCodeRuntimeAssets") {
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.py"))
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.lock.json"))
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_assets.py"))
        outputs.dir(generatedRuntimeAssets)
        commandLine(
            pythonCommand,
            repoRoot.resolve("scripts/prepare_android_runtime_assets.py").absolutePath,
            "--output-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--lock-file",
            repoRoot.resolve("runtime_tools/termux_assets.lock.json").absolutePath,
        )
    }

val prepareOpenCodeRuntimeNativeLibs =
    tasks.register<Exec>("prepareOpenCodeRuntimeNativeLibs") {
        dependsOn(prepareOpenCodeRuntimeAssets)
        inputs.dir(generatedRuntimeAssets)
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py"))
        outputs.dir(generatedRuntimeJni)
        commandLine(
            pythonCommand,
            repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py").absolutePath,
            "--linux-assets-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--output-dir",
            generatedRuntimeJni.get().asFile.absolutePath,
        )
    }

val releaseStoreFile =
    (
        System.getenv("OPENCODE_STORE_FILE")
            ?: findProperty("OPENCODE_STORE_FILE")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseStorePassword =
    (
        System.getenv("OPENCODE_STORE_PASSWORD")
            ?: findProperty("OPENCODE_STORE_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseKeyAlias =
    (
        System.getenv("OPENCODE_KEY_ALIAS")
            ?: findProperty("OPENCODE_KEY_ALIAS")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseKeyPassword =
    (
        System.getenv("OPENCODE_KEY_PASSWORD")
            ?: findProperty("OPENCODE_KEY_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val hasReleaseSigning =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() } &&
        releaseStoreFile!!.let { path ->
            val resolved =
                if (File(path).isAbsolute) {
                    File(path)
                } else {
                    File(rootProject.projectDir, path)
                }
            resolved.isFile
        }









val bundledOpenCodeVersion: String =
    run {
        val manifest = file("src/main/assets/local-runtime-manifest.json")
        check(manifest.isFile) {
            "local-runtime-manifest.json is missing; run scripts/build.ps1 so the packager can stage the assets"
        }
        Regex("\"openCodeVersion\"\\s*:\\s*\"([^\"]+)\"")
            .find(manifest.readText())
            ?.groupValues
            ?.get(1)
            ?: error("local-runtime-manifest.json has no openCodeVersion")
    }
val shellVersionName = "1.4.5"

android {
    namespace = "com.tianercode.opencode"
    compileSdk = 35

    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.tianercode.opencode"
        minSdk = 29
        targetSdk = 35
        versionCode = 72
        versionName = "$shellVersionName+oc$bundledOpenCodeVersion"

        
        
        buildConfigField("String", "OPENCODE_VERSION", "\"$bundledOpenCodeVersion\"")
        buildConfigField("String", "DISPLAY_VERSION", "\"$shellVersionName · OpenCode $bundledOpenCodeVersion\"")

        
        
        
        
        
        
        
        
        val updateManifestUrl =
            (project.findProperty("openCode.updateManifestUrl") as String?)
                ?.takeIf { it.isNotBlank() }
                .orEmpty()
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")

        
        
        
        
        
        
        
        
        
        
        

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "distribution"
    flavorDimensions += "abi"
    productFlavors {
        
        
        
        
        create("github") {
            dimension = "distribution"
        }
        
        create("fdroid") {
            dimension = "distribution"
        }

        
        
        
        
        
        
        
        
        
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x8664") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                val storeFilePath = releaseStoreFile!!
                storeFile =
                    if (File(storeFilePath).isAbsolute) {
                        File(storeFilePath)
                    } else {
                        File(rootProject.projectDir, storeFilePath)
                    }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                
                
                
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    testOptions {
        unitTests {
            
            
            isReturnDefaultValues = true
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
        buildConfig = true
    }
    sourceSets {
        getByName("main").jniLibs.srcDir(generatedRuntimeJni)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        noCompress += "tflite"
        
        
        
        
        
        
        
        
        noCompress += "targz"
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareOpenCodeRuntimeNativeLibs)
}

dependencies {
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    implementation(files("libs/geckoview-150-trimmed.aar"))
    
    
    
    
    implementation("org.yaml:snakeyaml:2.3")
    
    implementation("androidx.core:core-ktx:1.15.0")

    
    
    
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    
    
    
    
    
    

    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.commons:commons-compress:1.27.1")

    
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    
    testImplementation("junit:junit:4.13.2")
    
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}






tasks.register("generateNoticeAggregate") {
    doLast {
        val noticeEntryNames =
            listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md", "NOTICE", "NOTICE.txt")
        val outputFile = repoRoot.resolve("THIRD_PARTY_LICENSES/NOTICE-aggregate.txt")
        val artifacts =
            configurations.getByName("releaseRuntimeClasspath").incoming.artifacts.artifacts
                .sortedBy { it.id.componentIdentifier.displayName }

        fun readNoticeFrom(zip: ZipFile): Pair<String, String>? {
            for (name in noticeEntryNames) {
                val entry = zip.getEntry(name) ?: continue
                return name to zip.getInputStream(entry).bufferedReader().use { reader -> reader.readText() }.trim()
            }
            return null
        }

        val sections = mutableListOf<String>()
        artifacts.forEach { artifact ->
            val file = artifact.file
            if (!file.name.endsWith(".jar") && !file.name.endsWith(".aar")) return@forEach
            val found = mutableListOf<Pair<String, String>>()
            ZipFile(file).use { outer ->
                readNoticeFrom(outer)?.let { found += it }
                
                outer.getEntry("classes.jar")?.let { classesEntry ->
                    val tempJar = File.createTempFile("classes", ".jar")
                    try {
                        outer.getInputStream(classesEntry).use { input -> tempJar.outputStream().use { out -> input.copyTo(out) } }
                        ZipFile(tempJar).use { inner -> readNoticeFrom(inner)?.let { found += it } }
                    } finally {
                        tempJar.delete()
                    }
                }
            }
            if (found.isNotEmpty()) {
                val (entryName, text) = found.first()
                sections +=
                    "==== ${artifact.id.componentIdentifier.displayName} ($entryName) ====\n$text\n"
            }
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            "# Aggregated NOTICE files\n" +
                "# Generated by `./gradlew :app:generateNoticeAggregate` (see scripts/generate_notice_aggregate.sh) " +
                "from the resolved releaseRuntimeClasspath - do not hand-edit.\n" +
                "# Artifacts with no embedded NOTICE file are omitted; that is not a claim they have none.\n\n" +
                sections.joinToString("\n"),
        )
        println("Wrote ${sections.size} embedded NOTICE file(s) out of ${artifacts.size} artifacts to ${outputFile.path}")
    }
}
