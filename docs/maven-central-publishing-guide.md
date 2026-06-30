# K-MVI Maven Central 发布指南

## 目录

- [整体流程](#整体流程)
- [当前状态总结](#当前状态总结)
- [详细步骤](#详细步骤)
  - [1. 注册 Maven Central Portal](#1-注册-maven-central-portal)
  - [2. 准备 GPG 签名密钥](#2-准备-gpg-签名密钥)
  - [3. 添加 Javadoc JAR](#3-添加-javadoc-jar)
  - [4. 添加 GPG Signing 插件](#4-添加-gpg-signing-插件)
  - [5. 添加 Maven Central 发布插件](#5-添加-maven-central-发布插件)
  - [6. 配置发布凭证](#6-配置发布凭证)
  - [7. 去除 SNAPSHOT 后缀](#7-去除-snapshot-后缀)
  - [8. 执行发布](#8-执行发布)
- [CI/CD 改造](#cicd-改造)
- [完整配置文件参考](#完整配置文件参考)

---

## 整体流程

Maven Central 的发布链路在 2026 年已统一到 **Central Portal**（[central.sonatype.com](https://central.sonatype.com)），取代了旧版 Sonatype OSSRH。完整流程：

```
注册 Namespace → 准备 GPG 密钥 → 添加 Javadoc JAR
→ 配置 Signing 插件 → 添加 Central Portal 发布插件
→ 配置凭证 → 执行发布 → Portal 自动验证 → 自动上线
```

---

## 当前状态总结

| 项目 | 当前状态 | 操作 |
|------|----------|------|
| **POM**（name/desc/license/scm/developers） | ✅ 已完整配置 | 无需改动 |
| **Sources JAR** | ✅ 已配 `withSourcesJar()` | 无需改动 |
| **Javadoc JAR** | ❌ 缺失 | 需添加 `withJavadocJar()` |
| **Artifact 签名 (GPG)** | ❌ 缺失 | 需添加 `signing` 插件 |
| **Maven Central 发布目标** | ❌ 只有 GitHub Packages | 需添加 Central Portal 端点 |
| **Central Portal 账号 + Namespace** | ❌ 未注册 | 需注册并验证 |
| **CI 自动发布** | ❌ 只有手动 GitHub Packages workflow | 可改造复用 |

---

## 详细步骤

### 1. 注册 Maven Central Portal

1. 访问 [https://central.sonatype.com](https://central.sonatype.com) 注册账号。
2. 导航到 **Publishing → Namespaces**，申请验证 **`cc.colorcat.mvi`**。
3. 验证方式：
   - **DNS TXT 记录**：在 `colorcat.cc` 域名 DNS 中添加指定的 TXT 记录。
   - **或 GitHub 验证文件**：在 GitHub Pages 或仓库中添加验证文件。
4. 验证通过后，该 Namespace 即可用于发布。

> 如果已通过旧版 Sonatype JIRA 申请过 `cc.colorcat.mvi`，Central Portal 会自动关联，无需重新验证。

### 2. 准备 GPG 签名密钥

Maven Central **强制要求**所有 artifact 使用 GPG 签名。

#### 生成密钥

```bash
gpg --full-generate-key
```

交互选项参考：

| 选项 | 建议值 |
|------|--------|
| 密钥类型 | RSA and RSA (default) |
| 密钥长度 | 4096 |
| 有效期 | 0（永不过期） |
| 姓名 | ccolorcat |
| 邮箱 | 与开发者信息一致 |

#### 查看密钥 ID

```bash
gpg --list-keys
```

输出示例：

```
pub   rsa4096 2026-01-01 [SC]
      A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6E7F8A9B0
uid           [ultimate] ccolorcat <ccolorcat@example.com>
sub   rsa4096 2026-01-01 [E]
```

- **Key ID**：长密钥 ID 的最后 8 位（上面的 `E7F8A9B0`），或完整指纹。
- 发布时使用 **完整指纹**（推荐）或长 Key ID。

#### 上传公钥到 Keyserver

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID_OR_FINGERPRINT>
```

可选的 keyserver：

- `keyserver.ubuntu.com`
- `keys.openpgp.org`
- `pgp.mit.edu`

#### 导出私钥（用于 Gradle 配置）

推荐导出为 ASCII 格式：

```bash
gpg --armor --export-secret-keys <KEY_ID_OR_FINGERPRINT>
```

输出以 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 开头。将此值保存为环境变量或文件，**绝不提交到 git**。

> **关于 secring.gpg**：GnuPG 2.1+ 不再生成 `secring.gpg` 文件。推荐使用环境变量方式传递私钥内容（见步骤 6），而非依赖文件路径。

### 3. 添加 Javadoc JAR

Maven Central 要求必须同时包含 **Sources JAR** 和 **Javadoc JAR**。

**编辑 `core/build.gradle.kts`**，在 `publishing.multipleVariants` 块中添加 `withJavadocJar()`：

```diff
 publishing {
     multipleVariants {
         allVariants()
         withSourcesJar()
+        withJavadocJar()
     }
 }
```

> **注意**：`withJavadocJar()` 要求模块的 `compileSdk` >= 34（当前为 34，满足条件）。如果遇到 Javadoc 生成失败，可以在 `android` 块内配置 Javadoc 选项：

```kotlin
tasks.withType<Javadoc> {
    options {
        this as StandardJavadocDocletOptions
        addBooleanOption("Xdoclint:none", true)  // 忽略 Javadoc 格式校验
    }
}
```

### 4. 添加 GPG Signing 插件

**编辑 `core/build.gradle.kts`**，在 `plugins` 块中添加：

```diff
 plugins {
     alias(libs.plugins.android.library)
     alias(libs.plugins.jetbrains.kotlin.android)
     id("maven-publish")
+    id("signing")
 }
```

在 `afterEvaluate` 块中添加签名配置：

```diff
 afterEvaluate {
     publishing {
         publications {
             register<MavenPublication>("release") {
                 // 现有 POM 配置不变
             }
         }
     }

+    signing {
+        sign(publishing.publications["release"])
+    }
 }
```

### 5. 添加 Maven Central 发布插件

推荐使用 **`io.github.gradle-nexus.publish-plugin`**，兼容 Central Portal。

#### 5a. 在 `gradle/libs.versions.toml` 添加版本

```toml
[versions]
# ... 在现有版本后添加
nexusPublish = "2.0.0"

[plugins]
# ... 在现有插件后添加
nexus-publish = { id = "io.github.gradle-nexus.publish-plugin", version.ref = "nexusPublish" }
```

#### 5b. 在根 `build.gradle.kts` 应用插件

```diff
 plugins {
     alias(libs.plugins.android.application) apply false
     alias(libs.plugins.jetbrains.kotlin.android) apply false
     alias(libs.plugins.android.library) apply false
+    alias(libs.plugins.nexus.publish) apply false
 }
```

#### 5c. 在根 `build.gradle.kts` 中配置 Nexus 发布仓库

在文件末尾添加：

```kotlin
apply(plugin = "io.github.gradle-nexus.publish-plugin")

nexusPublishing {
    repositories {
        sonatypeCentral {
            nexusUrl.set(uri("https://central.sonatype.com"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(
                providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
                    .orElse(providers.gradleProperty("mavenCentralUsername"))
            )
            password.set(
                providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
                    .orElse(providers.gradleProperty("mavenCentralPassword"))
            )
        }
    }
}
```

> **注意**：`sonatypeCentral` 是 Nexus Publish Plugin 2.x 为 Central Portal 提供的新 Repository 类型。旧版 OSSRH 用户使用 `sonatype()` 或 `mavenCentral()`，但 Central Portal 统一使用 `sonatypeCentral`。

### 6. 配置发布凭证

推荐通过 **环境变量** 传递敏感信息，避免提交到 git。

#### 环境变量方式（推荐）

在 `~/.zshrc` 或 CI Secrets 中设置：

```bash
# GPG 签名密钥——将 GPG 私钥的 ASCII 内容直接赋值
export ORG_GRADLE_PROJECT_signingKey=$(cat /path/to/private-key.asc)

# GPG 密钥的密码短语
export ORG_GRADLE_PROJECT_signingPassword="your-gpg-passphrase"

# Maven Central Portal 账号
export MAVEN_CENTRAL_USERNAME="your-central-portal-username"
export MAVEN_CENTRAL_PASSWORD="your-central-portal-password"
```

#### `gradle.properties` 方式（不提交 git）

在项目根目录创建 `gradle.properties`（已存在，在末尾追加）：

```properties
# 以下内容不要提交到 git
signing.keyId=LAST_8_CHARS
signing.password=your-gpg-passphrase
signing.secretKeyRingFile=/Users/you/.gnupg/secring.gpg
mavenCentralUsername=your-central-portal-username
mavenCentralPassword=your-central-portal-password
```

> 注意：`gradle.properties` 当前已提交到 git。如果选择此方式，务必将 `gradle.properties` 添加到 `.gitignore`，或另建 `local.properties` / 使用环境变量。

### 7. 去除 SNAPSHOT 后缀

**编辑 `gradle/libs.versions.toml`**，将版本号改为正式版本：

```diff
 [versions]
 # ...
-versionName = "1.4.4-SNAPSHOT"
+versionName = "1.4.4"
```

> 版本号规则：
> - **正式版**：`1.4.4`（不带后缀）
> - **SNAPSHOT 版**：`1.4.5-SNAPSHOT`（仅在开发阶段使用）
>
> 正式版发布后，可将版本号推进到下一个 SNAPSHOT 版本继续开发。

### 8. 执行发布

#### 一次性发布（开发机）

```bash
# Step 1: 发布到 Central Portal Staging
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

或分步执行（更可控）：

```bash
# Step 1: 仅发布，不关闭/释放
./gradlew publishReleasePublicationToSonatypeCentralRepository

# Step 2: 关闭 Staging Repository（触发验证）
./gradlew closeSonatypeCentralStagingRepository

# Step 3: 释放到 Maven Central
./gradlew releaseSonatypeCentralStagingRepository
```

> **Nexus Publish Plugin 2.x + Central Portal** 的 task 命名略有不同。如果不确定，先运行 `./gradlew tasks --group=publishing` 查看可用 task。

#### 验证发布结果

发布成功后，约 **5-10 分钟**后可在以下位置看到：

- **Central Portal**：https://central.sonatype.com/namespace/cc.colorcat.mvi
- **Maven Central 搜索**：https://search.maven.org/search?q=cc.colorcat.mvi
- **直接引用**：

```kotlin
// build.gradle.kts
implementation("cc.colorcat.mvi:core:1.4.4")
```

---

## CI/CD 改造

当前 `.github/workflows/publish.yml` 只发布到 GitHub Packages。可以改造为同时或单独发布到 Maven Central。

### 方案：新增 Maven Central Workflow

新建 `.github/workflows/publish-maven-central.yml`：

```yaml
name: Publish to Maven Central

on:
  workflow_dispatch:
    inputs:
      version:
        description: 'Release version (e.g. 1.4.4)'
        required: true
        type: string

jobs:
  publish:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: gradle

      - name: Set release version
        run: |
          sed -i "s/versionName = \".*\"/versionName = \"${{ inputs.version }}\"/" gradle/libs.versions.toml

      - name: Run core tests
        run: ./gradlew :core:test

      - name: Publish to Maven Central
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingPassword: ${{ secrets.GPG_PASSPHRASE }}
        run: |
          ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中配置：

| Secret | 值 |
|--------|-----|
| `MAVEN_CENTRAL_USERNAME` | Central Portal 账号 |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal 密码 |
| `GPG_PRIVATE_KEY` | GPG 私钥的 ASCII 内容 |
| `GPG_PASSPHRASE` | GPG 私钥的密码短语 |

---

## 完整配置文件参考

### `core/build.gradle.kts`（完整版）

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
    id("signing")
}

android {
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
            withJavadocJar()
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
        exceptionFormat = org.gradle.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// 可选的 Javadoc 容错配置（遇到 Javadoc 报错时启用）
// tasks.withType<Javadoc> {
//     options {
//         this as StandardJavadocDocletOptions
//         addBooleanOption("Xdoclint:none", true)
//     }
// }

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
    }

    signing {
        sign(publishing.publications["release"])
    }
}
```

### 根 `build.gradle.kts`（新增内容）

```kotlin
// 在文件末尾添加
apply(plugin = "io.github.gradle-nexus.publish-plugin")

nexusPublishing {
    repositories {
        sonatypeCentral {
            nexusUrl.set(uri("https://central.sonatype.com"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(
                providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
                    .orElse(providers.gradleProperty("mavenCentralUsername"))
            )
            password.set(
                providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
                    .orElse(providers.gradleProperty("mavenCentralPassword"))
            )
        }
    }
}
```

---

## 常见问题

### Q: Javadoc 生成报错怎么办？

大多数情况下，Kotlin + Android 项目的 Javadoc（准确说是 Dokka）需要特殊配置。如果 `withJavadocJar()` 失败，建议切换为 **Dokka**：

```kotlin
// 在根 build.gradle.kts 添加 Dokka 插件
plugins {
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

// 在 core/build.gradle.kts 应用
plugins {
    id("org.jetbrains.dokka")
}
```

然后在 `afterEvaluate` 中将 Dokka JAR 附加到 publication。

### Q: 签名时报 "Cannot perform signing for a non-empty publication"？

确保在 `afterEvaluate` 块中调用 `sign()`，因为 Maven 的 publication 是在 `afterEvaluate` 中才注册完成的。

### Q: Central Portal 返回 "Invalid namespace"？

确认 Namespace `cc.colorcat.mvi` 已在 [central.sonatype.com](https://central.sonatype.com) 通过验证。

### Q: 如何发布 SNAPSHOT 版本？

SNAPSHOT 版本不需要签名，可直接发布到 Snapshot 仓库：

```bash
./gradlew publishReleasePublicationToSonatypeCentralSnapshotRepository
```

但需要注意，Central Portal 对 SNAPSHOT 的支持还在演化中，如果不需要 Snapshot 发布，推荐在开发阶段使用 GitHub Packages 作为快照仓库。
