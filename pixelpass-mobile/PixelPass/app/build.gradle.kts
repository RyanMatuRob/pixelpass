import java.net.NetworkInterface
import java.net.Inet4Address
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
// Add this below your plugins block
fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            // Skip loopback and inactive adapters
            if (intf.isLoopback || !intf.isUp) continue

            val name = intf.displayName.lowercase()
            val shortName = intf.name.lowercase()

            // Specifically target Wi-Fi adapters (Windows uses "wi-fi" or "wlan")
            if (name.contains("wi-fi") || name.contains("wireless") || shortName.contains("wlan")) {
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Failed to fetch IP, falling back to localhost")
    }
    return "10.0.2.2" // Fallback to emulator alias
}

android {
    namespace = "com.pixelpass"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pixelpass"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val dynamicHostIp = getLocalIpAddress()

    buildTypes {
        debug {
            // Point to Azure backend (or switch to "http://$dynamicHostIp:3000/" for local backend)
            buildConfigField("String", "BASE_URL", "\"https://pixelpass-api-ff4f1e.azurewebsites.net/\"")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"https://pixelpass-api-ff4f1e.azurewebsites.net/\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // Enable BuildConfig generation
    }
    testOptions {
        // Pure JVM tests use the real classes; android.util.Log is stubbed out
        // instead of throwing so logging never fails a unit test.
        unitTests.isReturnDefaultValues = true
    }
}


ksp {
    // Room writes its schema JSON here so migrations can be diffed and inspected.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.accompanist:accompanist-permissions:0.35.0-alpha")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.mlkit:face-detection:16.1.6")
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
}
