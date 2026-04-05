plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "1.9.24"
    kotlin("kapt") version "1.9.24"
    id("androidx.navigation.safeargs.kotlin") version "2.6.0"
}

android {
    namespace = "com.nezumi_ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nezumi_ai"
        minSdk = 30
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "nezumiai"
        
        // 大規模モデル対応のためのメモリ設定
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("C:/Users/mouse/mouse.jks")
            storePassword = "4t#DKk0"
            keyAlias = "key0"
            keyPassword = "4t#DKk0"
        }
    }

    buildTypes {
        debug {
            // デバッグ時もメモリ効率を優先
            isMinifyEnabled = false
        }
        
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    
    // UI
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.6.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.6.0")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // On-device LLM (MediaPipe tasks-genai - Gemma support)
    // MPImage / BitmapImageBuilder は tasks-genai の compile classpath に無い。tasks-core に含まれる。
    // Updated to 0.10.33 for tf_lite_end_of_vision and multimodal adapter support
    implementation("com.google.mediapipe:tasks-core:0.10.33")
    implementation("com.google.mediapipe:tasks-genai:0.10.33")

    // OAuth (Hugging Face)
    implementation("net.openid:appauth:0.11.1")

    // Markdown rendering
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
