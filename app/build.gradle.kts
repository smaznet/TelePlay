import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val telegramApiId: String = localProps.getProperty("telegram.apiId")?.trim().orEmpty()
val telegramApiHash: String = localProps.getProperty("telegram.apiHash")?.trim().orEmpty()
if (telegramApiId.toIntOrNull() == null || telegramApiHash.isEmpty()) {
    throw GradleException(
        "Missing Telegram credentials: add telegram.apiId=<number> and telegram.apiHash=<hash> " +
            "to local.properties (get them at https://my.telegram.org). local.properties is git-ignored."
    )
}

android {
    namespace = "com.smaznet.teleplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smaznet.teleplay"
        minSdk = 21
        targetSdk = 36
        versionCode = 5
        versionName = "1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    // "standard" keeps the Telegram-channel self-updater; "fdroid" is the F-Droid build: its own package name
    // (so it never fights over the signature with the standard build) and no in-app updater (F-Droid does the updating).
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("boolean", "UPDATES_ENABLED", "false")
        }
    }

    // F-Droid rejects the signed dependency-metadata blob AGP adds by default.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// T017 size gate. Measure: the compiled com.smaznet.teleplay.mtproto classes (release variant) are run
// through R8 on their own, keeping only the entry points the rest of the app uses (Rpc, DcConnection,
// DcSessionData, the F_* method classes and the hand-written service classes), so unused generated TL
// serializers/constructors are shrunk away. The resulting dex size must stay <= 1 MB. This is a standalone
// approximation of what the app's own R8 run keeps, not an exact slice of the final APK.
val checkTelegramLayerSize by tasks.registering {
    group = "verification"
    description = "Fails if the shrunk dex of the MTProto layer exceeds 1 MB."
    dependsOn("compileReleaseKotlin")
    val classesDir = layout.buildDirectory.dir("tmp/kotlin-classes/release/com/smaznet/teleplay/mtproto")
    val workDir = layout.buildDirectory.dir("tmp/telegramLayerSize")
    val sdkDir = android.sdkDirectory
    val buildTools = android.buildToolsVersion
    val platformJar = provider { File(android.sdkDirectory, "platforms/android-${android.compileSdk}/android.jar") }
    doLast {
        val classes = classesDir.get().asFile
        val work = workDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        val r8Jar = File(sdkDir, "build-tools/$buildTools/lib/d8.jar")
        check(r8Jar.exists()) { "d8.jar not found at $r8Jar" }
        val rules = File(work, "keep.pro")
        rules.writeText(
            """
            -dontwarn **
            -dontobfuscate
            -keep class com.smaznet.teleplay.mtproto.Rpc { *; }
            -keep class com.smaznet.teleplay.mtproto.ClientInfo { *; }
            -keep class com.smaznet.teleplay.mtproto.session.DcConnection { *; }
            -keep class com.smaznet.teleplay.mtproto.session.DcSessionData { *; }
            -keep class com.smaznet.teleplay.mtproto.session.RpcException { *; }
            -keep class com.smaznet.teleplay.mtproto.transport.TcpTransport { *; }
            -keep class com.smaznet.teleplay.mtproto.tl.generated.F_* { *; }
            -keep class com.smaznet.teleplay.mtproto.tl.generated.T_*${'$'}Companion { *; }
            -keep class com.smaznet.teleplay.mtproto.tl.ReqPqMulti { *; }
            -keepclassmembers class com.smaznet.teleplay.mtproto.tl.** { public static ** read(...); }
            """.trimIndent(),
        )
        val files = classes.walkTopDown().filter { it.extension == "class" }.map { it.absolutePath }.toList()
        check(files.isNotEmpty()) { "no mtproto classes compiled" }
        val javaHome = System.getProperty("java.home")
        val proc = ProcessBuilder(
            listOf(
                "$javaHome/bin/java", "-cp", r8Jar.absolutePath, "com.android.tools.r8.R8",
                "--release", "--min-api", "21", "--lib", platformJar.get().absolutePath,
                "--pg-conf", rules.absolutePath, "--output", work.absolutePath,
            ) + files,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "R8 failed:\n$out" }
        val size = work.listFiles { f -> f.extension == "dex" }!!.sumOf { it.length() }
        println("Telegram layer (R8-shrunk) dex size: $size bytes (limit 1048576)")
        if (size > 1_048_576L) throw GradleException("Telegram layer dex is $size bytes, over the 1 MB budget")
    }
}
