# a4miniprint Architecture

这份文档面向工程开发者，说明项目结构、构建方式、编码约束和安全边界。它不包含机器专用路径、签名证书身份或私有签名材料。

## 1. 项目定位

a4miniprint 是一个单 Activity Android 应用，用于把图片或 PDF 排版到 A4 纸并调用系统打印服务。应用包含两个顶层模式：

- `PHOTO_4X6`：把照片按近似 4×6 尺寸排版到 A4。
- `COLUMN_SPLIT`：把一张横向多栏图片拆成多张 A4 竖版页面。也接受 PDF，按页展开后逐页拆栏。

应用不直接连接打印机，不实现自定义打印服务，也不生成应用自有的图片或 PDF 文件。
唯一例外是 PDF 来源不可 seek 时在 `cacheDir` 生成的临时副本，见第 10 节。

核心流程：

1. 通过 Photo Picker、SAF 文件选择器或系统分享 / VIEW 入口获得图片 / PDF URI。
2. 图片走 `BitmapFactory` 读取边界和 EXIF orientation；PDF 走 `PdfRenderer` 读取页数和每页 point 尺寸。
3. 计算页面、格子、填充方式和分辨率。
4. 在后台线程解码图片（或栅格化 PDF 页）并写入 Android 打印框架提供的输出描述符。
5. 系统打印服务负责预览、页范围、份数、色彩和实际设备输出。

分栏拆印模式在进入系统打印预览前，还会使用应用内切分线预览辅助用户定位竖向切分线；该预览只加载降采样小图，不参与最终打印输出。

## 2. 技术栈

- 语言：Kotlin
- UI：Android View + ViewBinding
- 图片：`BitmapFactory`、`Bitmap`、`Canvas`、`Matrix`
- PDF：`android.graphics.pdf.PdfRenderer`（系统 API，无新增依赖）
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
│   │   ├── PdfDocumentHandle.kt
│   │   ├── PdfMetadataLoader.kt
│   │   ├── PhotoDecoder.kt
│   │   └── PreviewBitmapLoader.kt
│   ├── layout/
│   │   ├── ColumnSplitLayoutPlanner.kt
│   │   └── PrintLayoutPlanner.kt
│   ├── print/
│   │   ├── PhotoPrintDocumentAdapter.kt
│   │   └── PrintJobSubmitter.kt
│   ├── share/
│   │   └── ShareIntentParser.kt
│   └── ui/
│       ├── ColumnSplitPreviewView.kt
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
- 默认顶层任务模式。
- 默认分栏拆印栏数。
- 默认填充模式。
- 默认分辨率模式。
- 分栏预览最长边像素限制。
- 打印任务名。

新增 A6、5×7 等尺寸时，应先扩展 `PrintPreset` 和排版计算，再增加 UI 选项。

### data

`PrintModels.kt` 定义轻量数据模型：

- `PhotoMetadata`：URI、原始尺寸、EXIF orientation、来源类型（图片 / PDF 页）和 PDF 页索引。
- `PhotoSlot`：照片、页面矩形、填充方式、分辨率模式和可选源图裁剪区域。
- `PrintPage`：页面方向和一组照片槽位。
- 枚举：顶层任务模式、布局模式、填充模式、分辨率模式。

数据模型不持有 Bitmap，避免长时间保存大图内存。

### image

`ImageMetadataLoader`：

- 使用 `inJustDecodeBounds` 读取边界。
- 只读取 EXIF orientation。
- 不加载像素。

`PhotoDecoder`：

- 根据输出模式计算采样率。
- 支持分栏拆印传入的归一化裁剪区域。
- 解码为 `ARGB_8888`。
- 修正 EXIF 方向。
- 分栏拆印先在修正 EXIF 后的图上裁出左右栏，再进入统一渲染流程。
- 将横版图片旋转为竖版。
- PDF 页按来源类型分流到 `PdfRenderer`：只渲染目标栏区域到 A4 目标像素，
  不先渲染整页再裁剪；页面空白区域先铺白。
- PDF 的 renderer 由 `PdfDocumentHandle` 管理，一个解码器只在一次 `onWrite`
  任务内复用，任务结束由打印适配器调用 `close()`。
- 由打印适配器在每张图绘制后立即回收。

`PdfDocumentHandle`：

- 从 `content://` 或 `file://` 打开可 seek 的文件描述符并构造 `PdfRenderer`。
- `PdfRenderer` 接管描述符，`close()` 时一并关闭；构造失败时主动关闭描述符。

`PdfMetadataLoader`：

- 读取 PDF 页数和每页 point 尺寸（`PdfRenderer.Page.width / height`）。
- 直接打开失败（云盘 / pipe provider 不可 seek）时，复制到
  `cacheDir/pdf-source-<uuid>.pdf` 后重试。
- 返回每页一个 `PhotoMetadata`（`sourceType = PDF_PAGE`、`pdfPageIndex`）。
- 提供 `clearCachedPdfs()`，只在选择新 PDF 或冷启动时清理临时副本。

`PreviewBitmapLoader`：

- 仅为分栏切分线预览加载一张小图。
- 图片按 `previewMaxEdgePx` 降采样，默认最长边为 1024px。
- PDF 打开 renderer，渲染当前预览页到最长边 1024px 的小图后关闭。
- 修正 EXIF 方向，并在生成新 Bitmap 时回收中间对象。
- 不参与最终打印输出，不生成文件。

### layout

`PrintLayoutPlanner` 统一输出 A4 竖版页面：

- 四宫格模式按 4 张一组分页。
- 混合横竖照片不按方向重新分组。
- 单页模式每页一张。
- 当前格子为 `99mm × 148.5mm`。
- 左、中、右水平空隙均匀分配，各为 `4mm`。

`ColumnSplitLayoutPlanner` 负责分栏拆印：

- 支持 2、3、4 栏。
- 每条切分线使用独立滑杆微调，内部按 0.1% 步进计算。
- 默认等分，用户可调整切分线位置。
- 按左到右顺序生成多张 A4 竖版页面。
- 第一版不自动识别栏边界，只做几何切分。

分栏预览由 `ColumnSplitPreviewView` 绘制，当前切分线来自同一份滑杆状态：

- 预览 Bitmap 由 `MainActivity` 统一持有，主界面和分享弹窗共享同一份内存。
- 仅分栏拆印模式加载预览；切回 4×6 模式、重新选择多图或 Activity 销毁时立即回收。
- 加载结果带代际校验，旧的加载任务完成后不会覆盖新的选择。
- PDF 每个源页保存独立的切分线；预览可翻页，滑杆只编辑当前页。
- 栏数变化时所有页的切分线重置为等分。

### print

`PrintJobSubmitter`：

- 创建打印适配器。
- 设置默认 A4 打印属性。
- 提交给系统 `PrintManager`。

`PhotoPrintDocumentAdapter`：

- `onLayout` 返回页数。
- `onWrite` 根据系统请求的页范围过滤页面。
- 每次 `onWrite` 创建独立解码器，避免上一次被取消的写入与新的写入共享
  非线程安全的 `PdfRenderer`。
- 在后台线程解码、绘制并写入 PDF。
- 完成后关闭 `PdfDocument`、解码器（及其 renderer）和后台线程池。
- 输出目标是系统传入的 `ParcelFileDescriptor`。

### share

`ShareIntentParser`：

- 兼容 `ACTION_SEND`、`ACTION_SEND_MULTIPLE` 和 `ACTION_VIEW`。
- 支持 `EXTRA_STREAM`、`ClipData` 和 `intent.data`。
- 去重 URI。
- 过滤为本地可读的 `content` 或 `file` URI。
- 不申请持久 URI 授权，仅使用系统当前授予的读取权限。

### ui

`MainActivity`：

- 使用系统 Photo Picker 选择图片。
- 使用 `OpenDocument("application/pdf")` 选择 PDF。
- 分享 / VIEW 入口按 MIME 分流；图片和 PDF 混合或一次多个 PDF 直接提示拒绝。
- 分享入口载入图片后直接进入主界面，不显示参数确认弹窗。
- 维护布局、填充和分辨率选项。
- 每次载入新图片时重置为默认 `PHOTO_4X6` 模式。
- 载入 PDF 后进入 `COLUMN_SPLIT`，禁用 `4×6` 选项和「输出精度」，固定 300DPI。
- PDF 预览支持「上一页 / 下一页」，切分线按源页独立保存。
- 动态显示应用版本号。
- 分栏拆印模式下显示切分线预览，并共享同一份降采样 Bitmap。
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
- `FILL`：拉伸填满目标区域；仅用于分栏拆印，可能造成文字变形。

### 分辨率模式

- `PRINT_300_DPI`：按目标输出尺寸采样，生成更快、体积更小。
- `ORIGINAL`：不额外采样，适合保存为 PDF 后放大查看或二次裁切，但内存和体积更高。

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

例外：PDF 来源不可 seek（部分云盘 / pipe provider）时，允许在 `cacheDir`
生成 `pdf-source-*` 临时副本，且必须在下次选择 PDF 或冷启动时清理。
不得在一轮 `onWrite` 结束或 Activity 销毁时删除，因为系统打印框架可能多次
回调 `onWrite`。

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
- 分栏拆印 2、3、4 栏可用，切分线可独立微调。
- 分栏预览红线随滑杆实时移动，切回 4×6 模式后释放预览内存。
- 分栏拆印的等比适配、拉伸填满和从左到右页面顺序可用。
- PDF 可经应用内选择、分享面板和「用其他应用打开」进入。
- 多页 PDF 输出页数 = 源页数 × 栏数，页序为源页 1 左→右、源页 2 左→右。
- PDF 模式禁用 4×6 和输出精度；预览可翻页，每页切分线独立。
- 不可 seek 的 PDF 来源走临时副本；退出 / 重进后无残留 `pdf-source-*`。
- 加密或损坏 PDF 提示失败且不崩溃。
- 真实打印机输出和切割位置符合预期。

## 13. 参考文档

- 隐私政策：`PRIVACY.md`
- 安全政策：`SECURITY.md`
- 贡献指南：`CONTRIBUTING.md`
- 第三方声明：`docs/third-party-notices.md`
