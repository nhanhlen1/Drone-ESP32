plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.drone.controller"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.drone.controller"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.1-protocol"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; warningsAsErrors = false }
    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.isIncludeAndroidResources = true
    }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// Switching the reference FC must execute tests again; inherited environment
// variables alone do not invalidate Gradle's test-result cache.
tasks.withType<Test>().configureEach {
    systemProperty("maven.repo.local", File(gradle.gradleUserHomeDir, "robolectric-maven").absolutePath)
    val sitlBinary = System.getenv("DRONE_SITL_BINARY") ?: ""
    inputs.property("droneSitlBinary", sitlBinary)
    inputs.property("droneSitlProfile", System.getenv("DRONE_SITL_PARAM_PROFILE") ?: "legacy")
    if (sitlBinary.isNotEmpty() && file(sitlBinary).isFile) inputs.file(sitlBinary)
}
