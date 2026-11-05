# a4miniprint Architecture

这份文档面向工程开发者，说明项目结构、构建方式、编码约束和安全边界。它不包含机器专用路径、签名证书身份或私有签名材料。

## 1. 项目定位

a4miniprint 是一个单 Activity Android 应用，用于把图片排版到 A4 纸并调用系统打印服务。应用不直接连接打印机，不实现自定义打印服务，也不生成应用自有的图片或 PDF 文件。

核心流程：

1. 通过 Photo Picker 或系统分享入口获得图片 URI。
2. 后台读取图片边界和 EXIF orientation。
3. 计算页面、格子、填充方式和分辨率。
4. 在后台线程解码图片并写入 Android 打印框架提供的输出描述符。
5. 系统打印服务负责预览、页范围、份数、色彩和实际设备输出。

## 2. 技术栈

- 语言：Kotlin
- UI：Android View + ViewBinding
- 图片：`BitmapFactory`、`Bitmap`、`Canvas`、`Matrix`
- EXIF：`androidx.exifinterface`
- 打印：`PrintManager`、`PrintDocumentAdapter`、`PdfDocument`
- 选择器：AndroidX Activity Result API
- 最低运行版本：API 31
- 目标版本：API 35
- 构建工具：Gradle Wrapper、Android Gradle Plugin

依赖约束：

- 只允许 AndroidX 和 Android 系统 API。
- 不引入图片加载库。
- 不引入打印第三方库。
- 不引入网络、广告、账号或分析 SDK。

## 3. 目录结构

```text
app/src/main/
├── AndroidManifest.xml
├── java/io/github/soroaqs/a4miniprint/
│   ├── config/
│   │   └── PrintConfig.kt
│   ├── data/
│   │   └── PrintModels.kt
│   ├── image/
│   │   ├── ImageMetadataLoader.kt
│   │   └── PhotoDecoder.kt
│   ├── layout/
│   │   └── PrintLayoutPlanner.kt
│   ├── print/
│   │   ├── PhotoPrintDocumentAdapter.kt
│   │   └── PrintJobSubmitter.kt
│   ├── share/
│   │   └── ShareIntentParser.kt
│   └── ui/
│       └── MainActivity.kt
└── res/
    ├── layout/
    └── values/
```

## 4. 模块职责

### config

`PrintConfig` 是输出规格的主要配置入口：

- A4 页面尺寸。
- 目标 DPI。
- 网格行列数。
- 默认布局模式。
- 默认填充模式。
- 默认分辨率模式。
- 打印任务名。

新增 A6、5×7 等尺寸时，应先扩展 `PrintPreset` 和排版计算，再增加 UI 选项。

### data

`PrintModels.kt` 定义轻量数据模型：

- `PhotoMetadata`：URI、原始尺寸和 EXIF orientation。
- `PhotoSlot`：照片、页面矩形、填充方式和分辨率模式。
- `PrintPage`：页面方向和一组照片槽位。
- 枚举：布局模式、填充模式、分辨率模式。

数据模型不持有 Bitmap，避免长时间保存大图内存。

### image

`ImageMetadataLoader`：

- 使用 `inJustDecodeBounds` 读取边界。
- 只读取 EXIF orientation。
- 不加载像素。

`PhotoDecoder`：

- 根据输出模式计算采样率。
- 解码为 `ARGB_8888`。
- 修正 EXIF 方向。
- 将横版图片旋转为竖版。
- 由打印适配器在每张图绘制后立即回收。

### layout

`PrintLayoutPlanner` 统一输出 A4 竖版页面：

- 四宫格模式按 4 张一组分页。
- 混合横竖照片不按方向重新分组。
- 单页模式每页一张。
- 当前格子为 `99mm × 148.5mm`。
- 左、中、右水平空隙均匀分配，各为 `4mm`。

### print

`PrintJobSubmitter`：

- 创建打印适配器。
- 设置默认 A4 打印属性。
- 提交给系统 `PrintManager`。

`PhotoPrintDocumentAdapter`：

- `onLayout` 返回页数。
- `onWrite` 根据系统请求的页范围过滤页面。
- 在后台线程解码、绘制并写入 PDF。
- 完成后关闭 `PdfDocument` 和后台线程池。
- 输出目标是系统传入的 `ParcelFileDescriptor`。

### share

`ShareIntentParser`：

- 兼容 `ACTION_SEND` 和 `ACTION_SEND_MULTIPLE`。
- 支持 `EXTRA_STREAM` 和 `ClipData`。
- 去重 URI。
- 过滤为本地可读的 `content` 或 `file` URI。
- 不申请持久 URI 授权，仅使用系统当前授予的读取权限。

### ui

`MainActivity`：

- 使用系统 Photo Picker。
- 支持分享确认窗口。
- 维护布局、填充和分辨率选项。
- 动态显示应用版本号。
- 使用单线程 executor 加载元数据。
- Activity 销毁时关闭 executor 并清理主线程回调。

## 5. 排版规格

当前预设是「A4 上的近似 4×6」。

严格 4×6 为 `101.6mm × 152.4mm`。两张严格 4×6 上下排列的总高度为 `304.8mm`，超过 A4 高度 `297mm`，因此使用：

```text
单格：99mm × 148.5mm
比例：2:3
```

A4 四宫格水平落位：

```text
第 1 列：4mm - 103mm
第 2 列：107mm - 206mm
```

未校准时左右边距均为 `4mm`。不要把剩余宽度只分摊给左右 margin，否则右列会贴到页面边缘，造成整体偏右。

`horizontalPrintCalibrationMm` 用于补偿真实打印机的物理原点偏差。当前默认值为 `-0.5mm`，对应实测左侧留白比右侧多约 `1mm` 的情况；如果换打印机后出现反向偏差，应先实测再调整该配置。

校准后的应用侧坐标为：

```text
第 1 列：3.5mm - 102.5mm
第 2 列：106.5mm - 205.5mm
```

应用侧左边距为 `3.5mm`，右边距为 `4.5mm`。若打印机物理原点向右偏约 `0.5mm`，实际纸张上的左右留白都会接近 `4mm`。

## 6. 填充与分辨率

### 填充方式

- `CONTAIN`：完整显示照片，不裁切，不变形；比例不匹配时留白。
- `COVER`：居中裁切并填满 2:3 格子；会丢失部分边缘。

### 分辨率模式

- `PRINT_300_DPI`：按目标输出尺寸采样，生成更快、体积更小。
- `ORIGINAL`：不额外采样，适合 PDF 放大查看或二次裁切，但内存和体积更高。

PDF 图像由 `PdfDocument` 以无损压缩方式嵌入。JPG 原文件体积是经过有损压缩后的体积，因此不能与 PDF 中的图像流体积直接类比。

## 7. 构建环境

推荐使用 Android Studio，也可以使用命令行工具。

需要：

- JDK 17 或 21。
- Android SDK Platform 35。
- Android SDK Build-Tools 35。
- Android Platform Tools。
- 项目自带 Gradle Wrapper。

### 7.1 命令行安装示例

以下命令以 macOS Homebrew 为例，其他平台请使用对应包管理器：

```bash
brew install openjdk@21
brew install --cask android-commandlinetools
```

设置 SDK 组件：

```bash
export ANDROID_HOME=<你的 Android SDK 路径>

yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  'platform-tools' \
  'platforms;android-35' \
  'build-tools;35.0.0'
```

### 7.2 配置 `local.properties`

在项目根目录创建或修改：

```properties
sdk.dir=<你的 Android SDK 路径>
```

`local.properties` 是本机环境文件，不要提交到版本库。

## 8. 构建命令

在项目根目录执行：

```bash
export JAVA_HOME=<你的 JDK 路径>
export GRADLE_USER_HOME="$PWD/.gradle"

./gradlew tasks
./gradlew assembleDebug --no-daemon
./gradlew assembleRelease --no-daemon
./gradlew clean --no-daemon
```

输出位置：

```text
app/build/outputs/apk/debug/a4-mini-print-v<version>-debug.apk
app/build/outputs/apk/release/a4-mini-print-v<version>.apk
```

当前版本号由 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName` 控制。

## 9. 编码规范

- Kotlin 代码保持模块化，每个包只负责一个明确职责。
- 用户可见文案放入 `res/values/strings.xml`，不要在代码中硬编码。
- 优先使用 ViewBinding，避免 `findViewById`。
- 后台耗时任务不得在主线程执行。
- Bitmap 使用完成后必须 `recycle()`。
- 文件和线程池必须在任务结束后释放。
- 注释使用中文说明关键流程和边界。
- 不为单个用例创建抽象。
- 不添加未使用的依赖、权限或配置。
- UI 变更必须同步更新 `strings.xml` 和布局文件。

## 10. 项目约束

必须遵守：

- 最终输出纸张保持 A4。
- 使用系统 Photo Picker，不自研相册。
- 使用系统打印服务，不实现自定义 `PrintService`。
- 不直接连接打印机，不使用 IPP。
- 不修改打印机配置或固件。
- 不引入第三方图片加载或打印库。
- 不申请网络和存储权限。
- 不加入广告、账号、遥测或分析功能。
- 应用不主动生成图片、PDF 或缓存文件。

系统打印服务为完成任务生成的内部临时文件由系统管理，不属于应用违规落盘。

## 11. Release 签名

Release 签名使用自签名 keystore，不需要付费。只有发布到 Google Play 等应用商店时，才可能涉及商店开发者账号费用。

签名材料必须保存在项目外或被 `.gitignore` 忽略：

```text
*.keystore
*.jks
release-signing.properties
```

`release-signing.properties` 使用如下结构，不要把真实密码提交到仓库：

```properties
storeFile=<keystore 文件>
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

生成 keystore 的示例：

```bash
keytool -genkeypair -v \
  -keystore <keystore 文件> \
  -alias <别名> \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

验证 APK 签名：

```bash
<Android SDK>/build-tools/<版本>/apksigner verify --print-certs <APK 路径>
```

安全要求：

- 私钥和密码必须离线备份。
- 不要将 keystore、密码或 `local.properties` 提交到公开仓库。
- 不要在文档中记录真实证书 DN、密码或机器路径。

## 12. 验证清单

发布前检查：

- `assembleDebug` 成功。
- `assembleRelease` 成功。
- `aapt2 dump badging` 确认包名、版本、`minSdk` 和 `targetSdk`。
- `apksigner verify` 通过。
- `zipalign -c 4` 通过。
- Manifest 不出现网络、存储、定位等非必要权限。
- Save as PDF 页面为 A4，四宫格坐标居中。
- 四宫格、每页一张、contain、cover、300DPI 和原始分辨率可用。
- 真实打印机输出和切割位置符合预期。

## 13. 参考文档

- 隐私政策：`PRIVACY.md`
- 安全政策：`SECURITY.md`
- 贡献指南：`CONTRIBUTING.md`
- 第三方声明：`docs/third-party-notices.md`
