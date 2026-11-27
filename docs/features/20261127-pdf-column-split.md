# PDF 输入 + 分栏拆印 实施记录

- 日期：2026-11-27
- 版本：1.0.9（versionCode 10）
- 方案：`docs/plans/20261127-pdf-column-split.md`
- 状态：已实施；构建通过；用户真机反馈整体功能、Save as PDF、逐页切分线与翻页观感正常；边界场景待抽查

## 1. 关键决策

| 决策点 | 结论 |
| --- | --- |
| 技术路线 | `android.graphics.pdf.PdfRenderer`，无新增依赖 |
| 数据模型 | `PhotoMetadata` 增加 `sourceType`、`pdfPageIndex`（均带默认值），图片路径零变化 |
| 每页一项 | PDF 的每个源页对应一个 `PhotoMetadata`，`selectedPhotos` 直接承载，下游 planner 不感知来源 |
| 逐页展开 | `ColumnSplitLayoutPlanner` 不改；`startPrint` 对 PDF 逐页 `plan()` 后 `flatMap` |
| 区域渲染 | 直接把裁切区域栅格化到 A4 目标像素，不渲染整页再裁 |
| renderer 生命周期 | 每个 `onWrite` 任务创建独立 `PhotoDecoder`，任务内复用 `PdfRenderer`，`finally` 关闭 |
| 不可 seek 兜底 | 先直接打开；失败后复制到 `cacheDir/pdf-source-<uuid>.pdf` 再试 |
| 缓存清理 | 选择新 PDF 时、冷启动（`savedInstanceState == null`）时清理 |
| PDF 精度 | 固定 300DPI，隐藏「输出精度」并忽略该选项 |
| 预览 | PDF 每页独立保存切分线；预览可翻页，滑杆只编辑当前页 |

## 2. 与方案的偏差（实施时修正）

1. **renderer 生命周期**：方案写「一个打印任务复用一个 renderer」，但适配器的解码器原本是字段级单例，而 `onWrite` 会取消上一个任务并立刻起新线程。改为每次 `onWrite` 新建 `PhotoDecoder`，`finally` 调用 `close()`，避免取消旧任务与新任务共享 `PdfRenderer`。
2. **异常映射**：API 35 文档确认：不可 seek 抛 `IllegalArgumentException`、需要密码或安全方案不支持抛 `SecurityException`、读取错误抛 `IOException`。实现不逐个区分，统一先走缓存兜底，仍失败则提示「文件可能已加密或损坏」；URI 权限问题仍按系统消息提示，不误报成加密。
3. **冷启动清理**：`onCreate` 在旋转时也会执行。清理加 `savedInstanceState == null` 条件，避免打印预览期间旋转误删正在使用的缓存 PDF。
4. **模式切换顺序**：PDF 载入时先写 `selectedPhotos` / `selectedPdf`，再 `check(task_split_radio)`；若原本已在分栏模式（`check` 不触发监听），手动调用一次 `updateTaskModeSections()` + `refreshPreviewForTaskMode()`。
5. **布局**：「输出精度」原本没有容器，新增 `resolution_section` LinearLayout 以便整块隐藏。
6. **file:// 打开**：`PdfDocumentHandle` 对 `file://` 用 `ParcelFileDescriptor.open()`，避免依赖 `ContentResolver` 对文件 scheme 的处理差异。

## 3. 实施过程

1. `data/PrintModels.kt`：新增 `PhotoSourceType`，`PhotoMetadata` 增加 `sourceType`、`pdfPageIndex`。
2. 新增 `image/PdfDocumentHandle.kt`：打开 `content://` / `file://` FD 并持有 `PdfRenderer`，构造失败时关闭 FD。
3. 新增 `image/PdfMetadataLoader.kt`：读取页数 / 每页 point 尺寸；不可 seek 时复制到 cache；提供 `clearCachedPdfs()`；返回 `PdfSource`。
4. `image/PhotoDecoder.kt`：按 `sourceType` 分流；PDF 分支按裁切区域计算目标像素，用 `Matrix` 平移 + 缩放后 `Page.render()`；`close()` 关闭 renderer。
5. `image/PreviewBitmapLoader.kt`：PDF 渲染当前预览页到最长边 1024px。
6. `print/PhotoPrintDocumentAdapter.kt`：解码器改为 `onWrite` 任务内创建并关闭。
7. `share/ShareIntentParser.kt`：接受 `ACTION_VIEW`。
8. `ui/MainActivity.kt`：新增 PDF 选择器、MIME 分流、PDF 状态、模式锁定、多页 `flatMap`、
   逐页切分线与预览翻页、缓存清理。
9. `AndroidManifest.xml`：注册 PDF 的 SEND / SEND_MULTIPLE / VIEW（content scheme）。
10. `strings.xml` / `activity_main.xml`：PDF 按钮、页数文案、逐页切分线提示、翻页控件、分辨率容器。
11. 版本号提升到 1.0.9（versionCode 10）。
12. 同步 `docs/architecture.md`、`README.md`、`PRIVACY.md`、`CHANGELOG.md`。

## 4. 构建验证

```text
./gradlew assembleDebug --no-daemon    -> BUILD SUCCESSFUL
./gradlew assembleRelease --no-daemon  -> BUILD SUCCESSFUL
```

APK 检查：

```text
aapt2 dump badging   -> package=io.github.soroaqs.a4miniprint versionCode=10 versionName=1.0.9
aapt2 dump permissions -> 只有 androidx 自动生成的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
apksigner verify --print-certs -> 签名校验通过
```

Manifest 未新增网络 / 存储权限。PDF 的 SEND / SEND_MULTIPLE / VIEW
过滤器已在合并后的 Manifest 中确认存在。

## 5. 验证状态

用户真机反馈：

- PDF 输入、分栏拆印和 Save as PDF 输出整体功能正确；区域渲染的 `Matrix`
  语义符合预期（不需要回退整页渲染）。
- PDF 逐页切分线与翻页观感正常；红线与滑杆在预览图就绪后一起更新。

仍需抽查：

1. 10 页以上 PDF × 2 栏 → 输出 2N 页，无 OOM，耗时与体积可接受。
2. 云盘 / 不可 seek 来源 → 走缓存副本后能打印；重进后无 `pdf-source-*` 残留。
3. 加密 / 损坏 PDF → 友好提示，不崩溃。
4. 图片路径回归：4×6 四宫格、每页一张、单图分栏拆印行为不变。
5. 微信 / 文件管理器的 PDF 分享与「用其他应用打开」是否都能出现 a4miniprint。
6. 真实打印机的物理边距与裁切。
7. 切换栏数后，所有页切分线重置为等分。
8. 打印预览期间旋转屏幕不影响临时副本。

## 6. 遗留问题

- PDF 表单域、批注、透明效果经 `PdfRenderer` 栅格化后可能与系统原样打印有差异。
- PDF 一律栅格化，不保留矢量；300DPI 下文字清晰度需真机抽查。
- 一次只支持一个 PDF，不支持图片 + PDF 混合。

## 7. 后续补充：PDF 逐页切分线（真机反馈后）

**触发**：用户真机试用后指出，PDF 每页排版通常不一致，必须能逐页独立调整切分线；
原方案「预览固定第 1 页、切分线全局应用」不满足使用场景。

**实现**：

- 栏数仍是全局选项；每个源页保存自己的一组切分线（数量 = 栏数 - 1）。
- `MainActivity` 用 `splitPositionsByPage` 保存逐页切分线，图片只有一项；
  `previewPageIndex` 决定当前预览页和滑杆绑定的那一份。
- 预览区新增「上一页 / 第 X / N 页 / 下一页」，仅在多页 PDF 时显示。
- 翻页时只重新渲染该页的预览小图，不重置该页切分线。
- 翻页时只先更新页码和按钮；新预览图加载完成后，图片、红线和滑杆一起切到该页，
  避免红线先于图片跳到新位置。
- 载入 PDF 时所有页切分线回到等分；从 PDF 切回图片时也回到等分；
  图片之间沿用上一次调整的切分线（保持原有行为）。
- 切换栏数时所有页切分线重置为等分（旧线的数量和语义都无法沿用）。
- 打印时 `flatMapIndexed` 逐页取该页切分线；页序仍是源页 1 左→右、源页 2 左→右。
- 组件复用 `ColumnSplitLayoutPlanner`，未改动 planner。

**未做**：没有加「一键复制到全部页」按钮；需求是逐页独立，避免引入额外入口。
如后续需要，可在预览区加一个复制按钮。

**验证**：用户真机反馈翻页与逐页切分线正常；红线、滑杆与预览图同步更新。

## 8. 改动文件清单

```text
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/io/github/soroaqs/a4miniprint/data/PrintModels.kt
app/src/main/java/io/github/soroaqs/a4miniprint/image/PdfDocumentHandle.kt        (新增)
app/src/main/java/io/github/soroaqs/a4miniprint/image/PdfMetadataLoader.kt        (新增)
app/src/main/java/io/github/soroaqs/a4miniprint/image/PhotoDecoder.kt
app/src/main/java/io/github/soroaqs/a4miniprint/image/PreviewBitmapLoader.kt
app/src/main/java/io/github/soroaqs/a4miniprint/print/PhotoPrintDocumentAdapter.kt
app/src/main/java/io/github/soroaqs/a4miniprint/share/ShareIntentParser.kt
app/src/main/java/io/github/soroaqs/a4miniprint/ui/MainActivity.kt
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
docs/architecture.md
docs/plans/20261127-pdf-column-split.md
docs/features/20261127-pdf-column-split.md                                        (新增)
README.md
PRIVACY.md
CHANGELOG.md
```
