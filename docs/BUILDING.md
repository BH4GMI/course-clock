# 构建、测试与发布

## 环境要求

| 部分 | 要求 |
| --- | --- |
| Android 应用 | JDK 21、Android SDK（`compileSdk 34`）。Gradle 由 wrapper 自带（8.13），无需单独安装 |
| 作息换算模块 | JDK 8 或更高。只用到 `javac` / `java` |
| 教务工具 | Python 3，只用标准库 |

Android SDK 位置由 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量给出，或写在
`android/local.properties`：

```properties
sdk.dir=C:/Users/<你>/AppData/Local/Android/Sdk
```

`local.properties` 是机器相关的，已被 `.gitignore` 排除。

---

## 作息换算模块

纯 Java，无第三方依赖，自带不依赖测试框架的自测（通过时退出码 0）：

```powershell
powershell -ExecutionPolicy Bypass -File coursetime/verify.ps1
```

手动编译与调用（`javac -d` 不会自建输出目录，所以先建）：

```powershell
cd coursetime
New-Item -ItemType Directory -Force -Path build/classes | Out-Null
javac -encoding UTF-8 -d build/classes (Get-ChildItem src -Recurse -Filter *.java).FullName
java -cp build/classes courseclock.coursetime.CourseTimeCli "A501（机房）" "1-5节"
java -cp build/classes courseclock.coursetime.CourseTimeCli --table
```

`--table` 打印程序内转录的完整作息表，便于与原表核对。标准输出固定按 UTF-8 编码；控制台
代码页不是 65001 时先执行 `chcp 65001`。

---

## Android 应用

```shell
cd android
./gradlew assembleDebug          # 调试包
./gradlew test                   # 单元测试
./gradlew clean                  # 清掉构建产物
```

调试包产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

单元测试用 Robolectric，**首次运行需要联网**下载 `android-all` 运行时 jar，之后走本地缓存。

校核分楼作息用到的测试课表是随仓库提交的测试资源
（`android/app/src/test/resources/sues_345_conflict_test.wakeup_schedule`），测试经 classpath
读取，不依赖工作目录，全新 clone 上直接可跑。它的格式与来源见
[EAMS-IMPORT.md](EAMS-IMPORT.md)。

### 关于依赖仓库

`android/build.gradle` 里除了 `google()` 与 `mavenCentral()` 还留着 `jcenter()`。jcenter 已
停服，处于只读归档状态，但少数老库只有它有制品（`cn.carbswang.android:NumberPickerView`
等）。`android.enableJetifier=true` 同样是为这些 support 库二进制保留的；把
`NumberPickerView` 换掉之后，这两处可以一并删除。

---

## 发布签名

发布包用你自己的密钥库签名。口令不写进任何文件，也不经过命令行。

### 1. 准备 `keystore.properties`

复制 `android/keystore.properties.example` 为 `android/keystore.properties`，至少写入
`storeFile`：

```properties
storeFile=C:/path/to/your-android-keystore.jks
keyAlias=your_key_alias
```

该文件已被 `.gitignore` 排除，不会进入版本库。密钥库里只有一个私钥条目时可以不写
`keyAlias`，构建会用 `keytool` 自动读出；有多个条目时必须写明，否则构建会明确报错而不是
随便挑一个。

### 2. 提供口令

口令只从环境变量读，不写进任何文件，也不经过命令行参数：

```powershell
$env:WAKEUP_KEY_PASSWORD = '...'
$env:WAKEUP_KEYSTORE = 'C:/path/to/your-android-keystore.jks'   # 可选，覆盖 storeFile
$env:WAKEUP_KEY_ALIAS = 'your_key_alias'                        # 可选，覆盖 keyAlias
cd android
./gradlew assembleRelease
```

没有提供口令就直接跑 `./gradlew assembleRelease` 时，构建会明确停止并提示设置
`WAKEUP_KEY_PASSWORD`，不会产出一个未签名的包冒充发布包。

### 3. 产物

```
android/app/build/outputs/apk/release/app-release.apk
```

---

## 常见问题

**`gradlew` 报 “running scripts is disabled on this system”**
用 `powershell -ExecutionPolicy Bypass -File ...` 调用 `coursetime/verify.ps1`，或先执行
`Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`。

**Gradle 报找不到 SDK**
设置 `ANDROID_HOME`，或写 `android/local.properties` 的 `sdk.dir`。注意 properties 文件里
反斜杠是转义符，用正斜杠或双反斜杠。

**首次构建很慢**
Robolectric 的运行时 jar 与 Android 构建工具链都在首次构建时下载。
