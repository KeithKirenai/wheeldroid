import java.util.Properties
import java.io.FileInputStream
import java.io.IOException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun toolOnPath(tool: String): Boolean {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val lookup = if (isWindows) "where" else "which"
    return try {
        val process = ProcessBuilder(lookup, tool).redirectErrorStream(true).start()
        process.waitFor() == 0
    } catch (e: IOException) {
        false
    }
}

val ccacheArgs = if (toolOnPath("ccache")) {
    listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
} else {
    emptyList()
}

android {
    namespace = "com.wiicompiled.mkw"
    compileSdk = 35
    // Read local.properties ndk.dir or fallback gracefully
    val localPropsFile = rootProject.file("local.properties")
    val localProps = Properties()
    if (localPropsFile.exists()) {
        localProps.load(localPropsFile.inputStream())
    }
    val propNdkDir = localProps.getProperty("ndk.dir")
    if (!propNdkDir.isNullOrBlank()) {
        val ndkFolder = file(propNdkDir)
        if (ndkFolder.exists()) {
            // ndkPath omitted to allow ndk.dir in local.properties
            val sourceProps = File(ndkFolder, "source.properties")
            if (sourceProps.exists()) {
                val sp = Properties()
                sp.load(sourceProps.inputStream())
                val rev = sp.getProperty("Pkg.Revision")
                if (!rev.isNullOrBlank()) {
                    ndkVersion = rev
                }
            }
        }
    }
    // Read local.properties cmake.dir if present
    val propCmakeDir = localProps.getProperty("cmake.dir")
    val cmakeVer = if (!propCmakeDir.isNullOrBlank()) {
        val cmakeFolder = file(propCmakeDir)
        val binCmake = File(cmakeFolder, "bin/cmake.exe")
        if (binCmake.exists()) {
            val p = ProcessBuilder(binCmake.absolutePath, "--version").start()
            val out = p.inputStream.bufferedReader().readLine() ?: ""
            val parts = out.split(" ")
            val ver = parts.find { it.count { c -> c == '.' } >= 2 } ?: "3.28.6"
            ver
        } else {
            cmakeFolder.name
        }
    } else {
        "3.28.6"
    }

    defaultConfig {
        applicationId = "com.wiicompiled.mkw"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20", "-fexceptions", "-frtti", "-O3", "-DNDEBUG", "-ffunction-sections", "-fdata-sections")
                arguments(
                    *(
                        listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections") + ccacheArgs
                    ).toTypedArray()
                )
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = cmakeVer
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("**/libmkw_base_shared.a")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
}
