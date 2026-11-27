# PDF 输入 + 分栏拆印 方案

- 日期：2026-11-27
- 状态：已实现；Save as PDF 与逐页切分线已经真机验证；边界场景待抽查
  （见 `docs/features/20261127-pdf-column-split.md`）
- 说明：本文保留实施前的设计原貌（含已作废的「预览固定第 1 页」等限制）；
  与最终实现的差异以实施记录为准
- 后续变更：真机反馈后追加「PDF 逐页独立切分线 + 预览翻页」，第 3 节对应非目标作废，
  实现细节见 `docs/features/20261127-pdf-column-split.md` 第 7 节
- 关联版本：v1.0.8 之后的下一个版本
- 背景：现有分栏拆印只接受图片；目标是把来源扩展为 PDF，核心功能仍是「横向多栏页 → 多张 A4 竖版页」。

## 1. 目标

把一张横向多栏图片拆成多张 A4 的能力，扩展到 PDF：

- 接受一个 PDF 文件（应用内选择或系统分享）。
- PDF 的每一页本身就是横向多栏页面（A3/A4 横版、试卷式 2 栏等）。
- 逐页按当前栏数和切分线拆印，输出多张 A4 竖版页面。
- 输出顺序：源页 1 的左→右，源页 2 的左→右，依此类推。

## 2. 已确认的决策

| 决策点 | 结论 |
| --- | --- |
| PDF 页面版式 | 横向多栏页；复用现有分栏拆印流程，只把图片来源换成 PDF 页 |
| 多页处理 | 全部页都拆；输出页数 = 源页数 × 栏数 |
| 不可 seek 的来源 | 允许复制一份临时 PDF 到应用 cacheDir |
| 技术路线 | `android.graphics.pdf.PdfRenderer`（系统 API，无新增依赖） |
| 栅格化 | 接受。拆栏必须栅格化，输出不是矢量 |

## 3. 非目标

- 不做 PDF 矢量透传打印（与拆栏互斥）。
- 不做 A4 拼版 / booklet / 多页合一。
- 不做 PDF 加密破解、OCR、页面编辑。
- 不在 `PHOTO_4X6` 模式使用 PDF（PDF 只进分栏拆印）。
- 不做 PDF 预览翻页：~~预览固定第 1 页，切分线按百分比应用于所有页~~（已作废：
  真机反馈后改为逐页独立切分线 + 预览翻页）。
- 不支持一次混合选择「图片 + PDF」或「多个 PDF」。

## 4. 技术设计

### 4.1 输入入口

Manifest 需要注册的过滤器（目前只有 `image/*` 的 SEND / SEND_MULTIPLE）：

- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` + `application/pdf`：
  覆盖系统分享面板、微信「分享 / 用其他应用打开」等 SEND 路径。
- `ACTION_VIEW` + `CATEGORY_DEFAULT` + `application/pdf`（scheme `content`）：
  覆盖「用其他应用打开」实际发 VIEW 的机型/版本。不加 `BROWSABLE`。
- 现有 `image/*` 过滤器保持不变。

「所有应用」列表只会显示 manifest 能匹配该 Intent 的应用，不存在绕过过滤器的入口；
PDF 不可见的原因是缺少 `application/pdf` 过滤器，不是系统限制。

两种同名入口要区分：

- 系统分享面板里的「打印」是 Android 打印服务的分享目标（print spooler），
  按原样打印文件，不做拆栏。应用无法成为该目标（需要实现 `PrintService`，
  与项目约束冲突）。要拆栏必须选 `a4miniprint` 这个应用。
- 微信图片的 SEND 路径已经能匹配；PDF 补齐过滤器后应出现在同一位置。
  首次实现时建议临时记录 `intent.action` / `intent.type` / `uri.scheme`，
  确认微信 PDF 实际走 SEND 还是 VIEW；两个都注册即可覆盖。
- PNG 的成功只能证明「图片查看器」这条路径发的是 SEND（当前只注册了 SEND 也能出现），
  不能证明「文档查看器」的 PDF 路径也发 SEND。两者是不同组件，实测前不假设。
  但实现上不需要区分：两个过滤器都注册，最终以「安装新包后微信 PDF 菜单里是否出现
  本应用」为准；若想确认具体动作，可在调试构建的版本行临时显示 `intent.action`。

应用内入口：新增「选择 PDF」按钮，使用
`ActivityResultContracts.OpenDocument` + `arrayOf("application/pdf")`。
SAF 选择器不需要存储权限。

来源判定：

- 应用内选择：由按钮决定来源类型。
- 系统分享 / VIEW：按 `intent.action`、`intent.type`、`contentResolver.getType(uri)` 判定。
- `ShareIntentParser` 需要接受 `ACTION_VIEW`（URI 来自 `intent.data`）。
- 混合或多数来源直接提示拒绝，不进入后续流程。

### 4.2 PDF 元数据与 seekable 兜底

新增 `PdfMetadataLoader`：

1. `contentResolver.openFileDescriptor(uri, "r")`，尝试构造 `PdfRenderer` 验证 FD 可 seek。
2. 不可 seek（部分云盘 / pipe provider）时：
   - 复制到 `cacheDir/pdf-source-<uuid>.pdf`；
   - 以 `file://` 打开，记录该缓存文件。
3. 读取页数与每页尺寸：
   - `PdfRenderer.pageCount`；
   - 逐页 `PdfRenderer.Page.width / height`，单位为 point（1/72 英寸）；
   - 立即 `close()` 每页与整个 renderer。
4. 返回：
   - `resolvedUri`：原 content URI 或缓存文件 URI；
   - `pageCount`；
   - 每页一个 `PhotoMetadata`（`sourceType = PDF_PAGE`，`pdfPageIndex = i`，
     `rawWidth/rawHeight` = 页面 point 尺寸）。

缓存生命周期：

- 每次选择 PDF 使用唯一文件名。
- 不在一轮 `onWrite` 结束后立即删除：系统可能多次回调 `onWrite`（改页范围、份数等）。
- 在下次选择 PDF、或下一次冷启动时清理旧的 `pdf-source-*` 文件。
- cacheDir 内容系统也可回收；需在 PRIVACY / architecture 中说明这是唯一的临时落盘场景。

微信等第三方分享场景：

- 传入的是对方 FileProvider 的 `content://` URI，通常映射到本地文件，FD 可 seek，
  兜底复制大概率不会触发。
- 临时读权限与接收方 Activity 绑定。系统打印预览盖在 `MainActivity` 上时 Activity
  仍然存活，`onWrite` 期间读取原 URI 是可行的；但不要依赖 Activity 被销毁后的读取。
- 首次真机验证时重点观察：选中 PDF 后进入系统打印预览，确认 `onWrite` 能读到原文件。
  若发现原 URI 在打印阶段失效，再把策略升级为「PDF 一律先复制到 cache」，
  而不是只在不可 seek 时复制。

### 4.3 数据模型

`PhotoMetadata` 增加带默认值的字段，图片路径零变化：

```kotlin
enum class PhotoSourceType { IMAGE, PDF_PAGE }

data class PhotoMetadata(
    val uri: Uri,
    val rawWidth: Int,
    val rawHeight: Int,
    val exifOrientation: Int,
    val sourceType: PhotoSourceType = PhotoSourceType.IMAGE,
    val pdfPageIndex: Int = 0
)
```

字段语义：图片的 `rawWidth/rawHeight` 是像素；PDF 页是 point。两者都只用于
宽高比和目标尺寸计算，不参与像素寻址。

PDF 选中后，`selectedPhotos` 直接承载「每页一项」，下游 planner 无需感知来源类型。
另外用一个 `selectedPdf` 状态保存 `resolvedUri / pageCount / cachedFile`，供 UI
展示和缓存清理。

### 4.4 布局

`ColumnSplitLayoutPlanner` 不改。`MainActivity.startPrint` 对 `selectedPhotos`
逐项调用 `plan(photo, ...)` 后 `flatMap`：

```text
源页 1 → A4(左栏)、A4(右栏)
源页 2 → A4(左栏)、A4(右栏)
...
```

分栏拆印的准入条件从「恰好一张图片」调整为：

- 图片：`selectedPhotos.size == 1`（保持现状）；
- PDF：所有条目 `sourceType == PDF_PAGE` 且来自同一个文档（一页一项）。

### 4.5 渲染

`PhotoDecoder` 顶部分流：

- `IMAGE`：现有 `BitmapFactory` 采样解码路径不变。
- `PDF_PAGE`：新增 `decodePdfPage(slot)`：
  1. 由 `sourceCropRect × 页面 point 尺寸` 得到裁切区域；
  2. 用与图片相同的 `CONTAIN` / `FILL` 逻辑计算目标像素（A4@300DPI ≈ 2480×3508）；
  3. 创建目标尺寸 Bitmap；
  4. `PdfRenderer.Page.render(bitmap, null, transform, RENDER_MODE_FOR_PRINT)`，
     用 transform 把裁切区域平移到原点并缩放到目标尺寸；
  5. 关闭 Page；Bitmap 交给现有 `renderPage` 绘制后被 `recycle()`。

关键点：

- **只渲染目标区域**，不先渲染整张 A3 再裁。A3@300DPI 整页约 17MP（约 70MB），
  区域渲染的目标只有 A4@300DPI（约 8.7MP / 35MB）。
- `PdfRenderer` 非线程安全。一个打印任务复用一个 renderer（懒加载），只在
  `onWrite` 的单线程 executor 中访问，并在任务 `finally` 中关闭。
- 若区域渲染的 transform 语义实测不符合预期，回退方案是整页渲染 + 现有 crop，
  正确性不受影响，仅内存峰值上升。实现时需以官方文档 + 实测确认坐标系原点/方向。
- `PhotoResolutionMode.ORIGINAL` 对 PDF 没有「原始像素」含义：PDF 选中时隐藏
  「输出精度」选项，固定 300 DPI。

错误处理：

- 加密 PDF：`SecurityException` → 提示「PDF 已加密，暂不支持」。
- 损坏/无法渲染：`IOException` → 提示具体失败，不崩溃。
- 页面尺寸为 0 或异常：跳过并提示，或整体失败（实现时选简单的一致行为）。

### 4.6 预览

`PreviewBitmapLoader` 按来源分流：

- 图片：现有逻辑。
- PDF：打开 renderer，渲染第 1 页到「最长边 1024」的小图，关闭 renderer。

切分线仍按 0–1000 的百分比保存，因此同一组切分线自然应用于所有源页。
UI 增加说明：「预览为第 1 页，切分线将应用于全部 N 页」。

限制：切分线是全局的，不能逐页调整。若扫描件的每页栏间空白位置不一致，
同一组切分线不能同时对齐所有页；第一版接受该限制，逐页切分线留作后续需求。

> 后续变更：真机反馈后已实现逐页独立切分线与预览翻页，本节限制已解除；
> 见 `docs/features/20261127-pdf-column-split.md` 第 7 节。

### 4.7 UI

- 入口：保留「选择照片」，新增「选择 PDF」。
- PDF 载入后：
  - 计数文案显示「PDF：N 页」；
  - 自动切到分栏拆印；
  - 禁用/隐藏 `4×6 照片` 选项（PDF 不用于该模式）；
  - 隐藏「输出精度」（固定 300DPI）；
  - 栏数默认仍为 3（保持现状；试卷一般由用户手动选 2 栏）。
- 打印按钮生成 `N × 栏数` 页。

### 4.8 系统打印页范围

输出页是「源页 × 栏」展开后的序列。系统打印预览里的页范围是**输出页**范围，
不是源 PDF 页码。README 中明确说明（例如 3 页 PDF × 2 栏 = 6 个输出页）。

### 4.9 现有代码中需要适配的具体点

| 位置 | 现状 | 需要的改动 |
| --- | --- | --- |
| `MainActivity.loadSplitPreviewBitmap()` | 用 `selectedPhotos.singleOrNull()`，多页 PDF 会返回 null | 图片仍要求单张；PDF 取第 1 页作为预览源 |
| `MainActivity.loadMetadata()` | 每次载入都重置为 `PHOTO_4X6` | PDF 载入后改为 `COLUMN_SPLIT` 并勾选对应单选 |
| `MainActivity.updateTaskModeSections()` | 任务模式组始终可见 | PDF 选中时禁用 `4×6 照片` 单选，避免切到无意义模式 |
| `MainActivity.startPrint()` | 分栏模式要求 `selectedPhotos.size == 1` | 图片保持单张；PDF 逐页 `plan()` 后 `flatMap` |
| `PreviewBitmapLoader` | 只走 `BitmapFactory` | 增加 PDF 分支；代际校验丢弃结果时也要关闭 renderer |
| `PhotoDecoder` | 只走 `BitmapFactory` | 增加 PDF 分支与 renderer 生命周期 |
| `PrintJobSubmitter` / `PhotoPrintDocumentAdapter` | 无 PDF 概念 | 错误映射（加密/损坏）；确认缓存文件 URI 可被解码器读取 |
| `ShareIntentParser` | 只接受 SEND / SEND_MULTIPLE | 接受 `ACTION_VIEW` |
| `AndroidManifest.xml` | 只有 `image/*` 的 SEND | 增加 PDF 的 SEND / SEND_MULTIPLE / VIEW |
| `strings.xml` / `activity_main.xml` | 只有照片入口与「N 张」文案 | 新增 PDF 入口、页数文案、预览占位、模式提示；PDF 下隐藏 4×6 / 输出精度相关控件 |
| 缓存清理 | 无 | 新选择、冷启动时清理 `pdf-source-*`；不要在一轮 `onWrite` 结束就删 |

## 5. 文件改动清单

| 文件 | 改动 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | SEND / SEND_MULTIPLE 增加 `application/pdf` |
| `data/PrintModels.kt` | `PhotoSourceType`、`PhotoMetadata` 新增带默认值字段 |
| `image/PdfMetadataLoader.kt`（新增） | seekable 检测、缓存兜底、页数/页尺寸 |
| `image/PhotoDecoder.kt` | PDF 分支 + `PdfRenderer` 复用与关闭 |
| `image/PreviewBitmapLoader.kt` | PDF 第 1 页小图预览 |
| `share/ShareIntentParser.kt` | 接受 `ACTION_VIEW`，配合 PDF MIME 分流 |
| `ui/MainActivity.kt` | PDF 入口、MIME 分流、状态、模式锁定、多页 flatMap、缓存清理 |
| `print/PrintJobSubmitter.kt` / `PhotoPrintDocumentAdapter.kt` | renderer 生命周期、错误映射（是否需要改动以解码器设计为准） |
| `layout/ColumnSplitLayoutPlanner.kt` | 不改（逐页调用） |
| `res/values/strings.xml`、`res/layout/activity_main.xml` | 新入口按钮、提示文案 |
| `docs/architecture.md` | 输入来源、PdfRenderer、缓存例外、目录结构 |
| `README.md` / `PRIVACY.md` / `CHANGELOG.md` | 用户可见说明 |
| `docs/features/20261127-pdf-column-split.md` | 完成后的实施记录 |

## 6. 验证

### 6.1 Agent 可自动化

- `./gradlew assembleDebug --no-daemon`
- `./gradlew assembleRelease --no-daemon`
- 静态检查：Manifest 未新增存储/网络权限。

### 6.2 需要用户真机实测

1. 单页 A3 横版 2 栏 PDF，选 2 栏 → 输出恰好 2 张 A4 竖版，左→右，
   Save as PDF 与源页对应栏逐像素比对。
2. 10 页以上 PDF × 2 栏 → 输出 2N 页，全程无 OOM，生成时间可接受。
3. 云盘来源（不可 seek）→ 走缓存副本后能打印；退出/重进后无残留文件。
4. 加密 PDF → 友好提示，不崩溃。
5. 图片路径回归：4×6 四宫格、每页一张、单图分栏拆印行为不变。
6. 真实打印机：满幅内容的边距、裁切、缩放情况（系统打印服务决定）。

无 adb 时的验证方式：

- 安装新 APK 后，直接在微信里打开 PDF → 「用其他应用打开 / 分享」，看应用列表里
  是否出现 `a4miniprint`。出现即说明 SEND 或 VIEW 至少有一个匹配成功，无需知道是哪一个。
- 用「Save as PDF」做前期验证：它与真实打印共用 `PdfDocument` + `Canvas` 渲染路径，
  能验证页数（N × 栏数）、页序、切分线位置、比例和文字清晰度；验证不了打印机物理边距。
- 想确认实际是 SEND 还是 VIEW：在调试构建的版本行临时显示 `intent.action` / `intent.type`，
  不依赖 adb。
- 也可以从系统文件管理器打开 PDF，验证 VIEW 过滤器是否生效。

## 7. 风险与待实测

| 风险 | 说明 / 应对 |
| --- | --- |
| 区域渲染 transform 语义 | 原点、方向、缩放需实测；失败则回退整页渲染 + crop |
| 满幅打印边距 | 输出页内容可贴纸边，打印机物理不可打印边距可能裁切或由系统缩放 |
| 内存 | 逐页渲染 + 立即 recycle；A4@300DPI 约 35MB 峰值可控 |
| 输出体积 | 页数放大（源页 × 栏数），300DPI 下 PDF 体积随页数线性增长 |
| URI 授权生命周期 | SAF / 微信临时授权在 Activity 存活期内可用；打印期间不重启进程即可。若实测打印阶段失效，改为一律缓存 |
| 微信 PDF 的 Intent 形式 | 不确定走 SEND 还是 VIEW，两种过滤器都注册；首次实现记录 action/type 确认 |
| 多页栏位不一致 | 切分线是全局的；扫描件每页栏间空白不同时无法同时对齐，第一版接受 |
| PDF 特色内容 | 表单域、批注、透明效果经 `PdfRenderer` 栅格化后可能与系统原样打印有差异，需抽查 |
| 页数放大后的耗时 | 输出页 = 源页 × 栏数，系统打印预览可能一次请求全部页；顺序渲染，注意生成耗时与输出体积 |
| 异常 PDF 兼容性 | `PdfRenderer` 对部分 PDF 特性支持有限，按错误提示降级 |
| 缓存与打印竞态 | 不在 onWrite 结束即删；按「下次选择 / 冷启动」清理 |

## 8. 文档与约束更新

需要同步修改的既有约束：

- `docs/architecture.md` 第 2 节：允许 `android.graphics.pdf.PdfRenderer`（系统 API）。
- 第 10 节「应用不主动生成图片、PDF 或缓存文件」：增加例外——
  仅当 PDF 来源不可 seek 时，在 `cacheDir` 生成临时 PDF 副本，并按上述规则清理。
- `PRIVACY.md`：说明 PDF 仅本地处理、临时副本不离开设备。
- `README.md`：PDF 输入、多页展开规则、输出页范围含义、预览仅第 1 页。
- `CHANGELOG.md`：面向用户记录新功能。

## 附录 A：现有「横向 2 栏 PNG → 2 张 A4」的行为

按当前代码推导（未真机实测）：

- 进入分栏拆印后**默认是 3 栏**（`PrintConfig.defaultColumnCount = 3`），
  必须先手选「2 栏」，否则会被切成 3 份。
- 选 2 栏后切分线默认 `[500]`（正中）；栏间空白不在正中时需用滑杆微调。
- 若每个半页的宽高比 ≈ 210:297（如 A3 横版两张 A4 并排），`CONTAIN` 输出
  恰好是两张 A4 竖版满页，页序左→右，无旋转。
- 半页比例不符时等比适配会留白（不变形）；「拉伸填满 A4」会变形，不建议用于文字。
- 2 栏是采样逻辑最吃紧的场景：半幅宽已接近 A4@300DPI 目标宽，原图不会被降采样，
  A3@300DPI 约 70MB 一次性解码 + 35MB 裁剪副本；600DPI 扫描件有 OOM 风险。
- 输出为满幅页面，真实打印机的物理边距可能裁切边缘内容，需真机验证。
