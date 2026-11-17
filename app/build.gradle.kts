import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun runGitCommand(vararg args: String): String = try {
    val proc = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0) output else ""
} catch (_: Exception) {
    ""
}

fun escapeJavaString(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n', '\r' -> append(' ')
            '\t' -> append(' ')
            else -> append(ch)
        }
    }
}

fun resolveGitRevision(): String {
    val sha = runGitCommand("rev-parse", "--short", "HEAD")
    if (sha.isEmpty()) return "unknown"
    val dirty = runGitCommand("status", "--porcelain").isNotEmpty()
    return if (dirty) "$sha-dirty" else sha
}

val isIdeSync = gradle.startParameter.taskNames.any { it.contains("prepareKotlinIdeaImport", ignoreCase = true) } ||
    System.getProperty("idea.sync.active") == "true"
val buildTimestamp: String = if (isIdeSync) {
    "IDE-SYNC"
} else {
    ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
}
val gitRevision: String = resolveGitRevision()
val gitBranch: String = runGitCommand("rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "unknown" }
val gitCommitCount: String = runGitCommand("rev-list", "--count", "HEAD").ifEmpty { "0" }
val gitCommitDate: String = runGitCommand("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d %H:%M %Z")
    .ifEmpty { "unknown" }
val gitCommitMessage: String = escapeJavaString(
    runGitCommand("log", "-1", "--format=%s")
        .ifEmpty { "unknown" }
        .take(80)
)

android {
    namespace = "com.bajajauto.roadsense"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bajajauto.roadsense"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("String", "GIT_SHA", "\"$gitRevision\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "GIT_COMMIT_COUNT", "\"$gitCommitCount\"")
        buildConfigField("String", "GIT_COMMIT_DATE", "\"$gitCommitDate\"")
        buildConfigField("String", "GIT_COMMIT_MESSAGE", "\"$gitCommitMessage\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("unitTestClasses") {
    description = "Alias task to compile unit test sources for Android Studio."
    group = "verification"
    dependsOn(tasks.matching { it.name.startsWith("compile") && it.name.endsWith("UnitTestSources") })
}

tasks.register("testClasses") {
    description = "Alias task to compile unit test sources."
    group = "verification"
    dependsOn(tasks.matching { it.name.startsWith("compile") && it.name.endsWith("UnitTestSources") })
}

