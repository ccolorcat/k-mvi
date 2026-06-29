plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
}

android {
//    namespace = "cc.colorcat.mvi"
    namespace = libs.versions.groupId.get()
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility(libs.versions.java.get())
        targetCompatibility(libs.versions.java.get())
    }

    kotlinOptions {
        jvmTarget = libs.versions.java.get()
    }

    publishing {
        multipleVariants {
            allVariants()
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}


afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = libs.versions.groupId.get()
                artifactId = project.name
                version = libs.versions.versionName.get()

                from(components["release"])

                pom {
                    name.set("K-MVI Core")
                    description.set("A lightweight, type-safe Android MVI library built on Kotlin Coroutines and Flow.")
                    url.set("https://github.com/ccolorcat/k-mvi")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("ccolorcat")
                            name.set("ccolorcat")
                            url.set("https://github.com/ccolorcat")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/ccolorcat/k-mvi.git")
                        developerConnection.set("scm:git:ssh://git@github.com/ccolorcat/k-mvi.git")
                        url.set("https://github.com/ccolorcat/k-mvi")
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/ccolorcat/k-mvi/issues")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/ccolorcat/k-mvi")
                credentials {
                    username = (project.findProperty("gpr.personal.user") ?: System.getenv("USERNAME")) as? String
                    password = (project.findProperty("gpr.personal.key") ?: System.getenv("TOKEN")) as? String
                }
            }
        }
    }
}
