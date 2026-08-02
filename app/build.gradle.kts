/* Modified by Sui9x on 2026-06-27 */

import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

android {
    val buildTime = System.currentTimeMillis()
    val baseVersionName = "1.2.4"
    namespace = "com.mja.reyamf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mja.reyamf"
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        versionName = "$baseVersionName-modded"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }
    packaging {
        resources.excludes.addAll(
            arrayOf(
                "META-INF/**",
                "kotlin/**"
            )
        )
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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
        languageVersion = "2.0"
    }
    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.wear)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.preference.ktx)

    compileOnly(project(":android-stub"))
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.hidden.compat)

    //never upgrade until new extension function
    //noinspection GradleDependency
    implementation(libs.ezxhelper)
    compileOnly(libs.xposed.api)

    //lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    //flexbox
    implementation(libs.flexbox)

    //dynamicanimation
    implementation(libs.androidx.dynamicanimation.ktx)

    //gson
    implementation(libs.gson)

    //material
    implementation(libs.material)

    //glide
    implementation (libs.glide)

    // byte buddy
    implementation(libs.byte.buddy.android)

    //lifecycle service
    implementation(libs.androidx.lifecycle.service)

    //room
    //implementation(libs.androidx.room.runtime)
    //implementation(libs.androidx.room.ktx)
    //ksp(libs.androidx.room.compiler)
 
    
    //hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)
}

val roomVersion = "2.8.4"
val sqliteJdbcVersion = "3.53.2.0"

dependencies {
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    ksp("androidx.room:room-compiler:$roomVersion") {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }

    ksp("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    ksp("org.xerial:sqlite-jdbc:$sqliteJdbcVersion:natives-android")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.xerial" && requested.name == "sqlite-jdbc") {
            useVersion(sqliteJdbcVersion)
            because("Room verifier needs sqlite-jdbc that can load on Android/Termux-like build env")
        }
    }
}

/*
val gitHash: String
    get() {
        val out = ByteArrayOutputStream()
        val cmd = exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return if (cmd.exitValue == 0)
            out.toString().trim()
        else
            "(error)"
    }

val isDirty: Boolean
    get() {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "diff", "--stat")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return out.size() != 0
    }
    */