plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}
android {
    namespace = "cn.jingqi.guard"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.jingqi.guard"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
tasks.withType<Test>().configureEach {
    val isolatedHome = rootProject.file(".tools/test-home")
    val isolatedTemp = rootProject.file(".tools/test-tmp")
    systemProperty("user.home", isolatedHome.absolutePath)
    systemProperty("java.io.tmpdir", isolatedTemp.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    doFirst { isolatedHome.mkdirs(); isolatedTemp.mkdirs() }
}
