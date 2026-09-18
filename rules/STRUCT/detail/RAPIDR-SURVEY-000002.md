# 调研 — RapidRAW 在 Android 导入/library/显影存储模型

- ID: RAPIDR-SURVEY-000002
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000001.md`（RapidRAW 总体构建与渲染管线调研）、`rules/STRUCT/detail/DNGLAB-SURVEY-000001.md`（dnglab 作为对照）、`rules/REVIEW/detail/FOTLAB-RAWLER-000001.md`（我们当前 rawler 用法）

> **范围声明**：基于 `external/RapidRAW` 的 **shallow clone（`--depth 1`，`v1.6.4`）**。本调研只做文档化，不修改上游源码。聚焦三个问题：(1) Android 上 library 导入如何实现；(2) app 内部是存文件还是仅存 URI；(3) 显影修改是就地改源文件、还是存修改流 metadata 每次重渲染、还是存工作流 metadata+缓存渲染且不碰源文件。

## TL;DR（结论先行）

| 问题 | 结论 |
| --- | --- |
| Android 上 library 导入如何实现 | 桌面用系统目录对话框拿真实路径；**Android 不弹系统选目录**，而是把 library 根定为 app 专属外部存储 `Android/media/<pkg>/.library`（`GetOrCreateInternalLibraryRoot` → `getExternalMediaDirs()[0]/.library`）。图片经 SAF 以 `content://` URI 取得，导入时**把字节复制进 `.library`**；LUT/缩略图同理走 ContentResolver 读取。 |
| 内部存文件还是仅存 URI | **存文件**：导入即把选中的 `content://` 源图复制进 `.library`（app 存储），源文件原处不动。库本身、缓存（`.lut_cache`/`thumbnails`）、sidecar（`<name>.rrdata` JSON）也都在 app 存储内。`content://` URI 只是「导入瞬间的临时来源」，复制后库引用的是本地路径。**不**依赖长期 URI 权限（未见 `takePersistableUriPermission`）。 |
| 显影修改模型 | **非破坏性（第三种）**：调整参数以 JSON 存于 sidecar（桌面源旁 `<name>.rrdata`；Android 同 `.library` 内）或库元数据；**源文件从不就地修改**；每次渲染从源重新解码+套用调整，并用 hash 缓存（内存 `full_transformed_cache`/`patched_warped_cache` + 磁盘缩略图）加速；导出写**新文件**（MediaStore `Pictures/RapidRaw`、`Download/RapidRaw`），不覆盖源。 |

## 1. Android 上 library 导入如何实现

### 1.1 library 根的确定（Android vs 桌面）

前端 `handleOpenFolder` 在 Android 与桌面走不同分支：

```511:525:src/hooks/useAppNavigation.ts
const handleOpenFolder = useCallback(async () => {
    const isAndroid = osPlatform === 'android';
    ...
    if (isAndroid) {
      selectedPath = await invoke<string>(Invokes.GetOrCreateInternalLibraryRoot);
    } else {
      const selected = await open({ directory: true, multiple: false, defaultPath: await homeDir() });
      ...
```

Rust 侧 `get_or_create_internal_library_root` 按平台选根：桌面用 `app_data_dir/library`，Android 用 `get_android_internal_library_root`：

```3122:3147:src-tauri/src/file_management.rs
fn get_internal_library_root_path(app_handle: &AppHandle) -> Result<std::path::PathBuf, String> {
    #[cfg(not(target_os = "android"))] { app_handle.path().app_data_dir()...join("library") }
    #[cfg(target_os = "android")] { crate::android_integration::get_android_internal_library_root() }
}
```

Android 的根来自 `getExternalMediaDirs()[0]/.library`——即 app 专属外部存储（scoped storage 下免权限）：

```580:627:src-tauri/src/android_integration.rs
pub fn get_android_internal_library_root() -> Result<PathBuf, String> {
    ...
    let dirs_array_obj = env.call_method(&context, "getExternalMediaDirs", "()[Ljava/io/File;", &[])...;
    ...
    let library_dir = media_path.join(".library");
    ...
}
```

子目录树用 `get_folder_tree`/`get_folder_children` 经普通 `fs` 遍历（因为 `.library` 在 app 可自由访问的外部媒体目录内，无需权限）：

```1227:1285:src-tauri/src/file_management.rs
fn get_folder_tree_sync(path, expanded_folders, show_image_counts) -> Result<FolderNode, String> {
    let root_path = Path::new(&path);
    if !root_path.is_dir() { return Err(...); }
    ...
    let (children, own_count) = scan_dir_lazy(root_path, &expanded_set, show_image_counts, true)...;
```

### 1.2 图片来源：SAF 的 `content://` URI + ContentResolver

Android 上图片经系统选择器以 `content://` URI 形式进入：

- `is_android_content_uri` 判断 `path.starts_with("content://")`（`android_integration.rs:66-68`）。
- `resolve_android_content_uri_name` 通过 `ContentResolver.query` + `OpenableColumns.DISPLAY_NAME` 取文件名（`android_integration.rs:199-305`）。
- `read_android_content_uri` 通过 `ContentResolver.openInputStream(uri)` 读全部字节（`android_integration.rs:308-368`）。
- LUT 导入同样走 `content://`：`useEditorActions.ts:137` 在 Android 下对 `content://` 调 `resolve_android_content_uri_name`；`lut_processing.rs:132,375` 也用 `read_android_content_uri`。

### 1.3 导出落盘：MediaStore

保存结果写 MediaStore（而非源文件）：

```442:577:src-tauri/src/android_integration.rs
pub fn save_image_bytes_to_android_gallery(...) -> save_bytes_to_android_media_store(..., "Pictures/RapidRaw", "android/provider/MediaStore$Images$Media", bytes)
pub fn save_file_bytes_to_android_downloads(...) -> save_bytes_to_android_media_store(..., "Download/RapidRaw", "android/provider/MediaStore$Downloads", bytes)
```

`save_bytes_to_android_media_store` 用 `ContentValues`（`_display_name`/`mime_type`/`relative_path`/`is_pending`）经 ContentResolver 写入，写完置 `is_pending=0` 提交。

## 2. app 内部会存储文件吗？还是仅存储 URI？

**会存储文件——导入即复制进 app 存储，而非仅存 URI。**

导入流程（`file_management.rs` 中 `import` 路径）对 `content://` 源的处理：

```3735:3791:src-tauri/src/file_management.rs
#[cfg(target_os = "android")]
if is_android_content_uri(source_path_str) {
    let resolved_name = resolve_android_content_uri_name(source_path_str)?;   // 取显示名
    let source_bytes = read_android_content_uri(source_path_str)?;           // ContentResolver 读字节
    ...
    fs::create_dir_all(&final_dest_folder)...;                              // destination_folder 在 .library 内
    ...
    fs::write(&dest_file_path, source_bytes).map_err(|e| e.to_string())?;   // 复制进 app 存储
    if settings.delete_after_import {
        log::info!("Skipping delete_after_import for Android content URI source: {}", source_path_str); // 不删 SAF 源
    }
    return Ok(());
}
```

要点：
- 导入时把 `content://` 源**整份字节 `fs::write` 复制**进 `destination_folder`（Android 上即 `.library` 目录树），并用模板重命名。
- **显式跳过 `delete_after_import`**：SAF 来源的源无法删除，进一步说明 `content://` 只是「导入瞬间的来源」，复制后库引用本地路径。
- **不请求长期 URI 权限**：全仓仅 `AndroidManifest.xml:31` 有 `android:grantUriPermissions="true"`，未见 `takePersistableUriPermission`/`getPersistedUriPermissions`。即 RapidRAW 不依赖「持久持有 content:// 权限、仅存 URI 长期引用」的方案，而是用「导入即复制」规避。

app 存储内实际落盘的内容（全部在 app 专属外部存储 / cache）：
- `Android/media/<pkg>/.library` — library 库（导入的图片副本 + 目录树）
- `.lut_cache`（外部媒体目录下，按 `blake3(uri)[:16]` 命名）— LUT 缓存（`android_integration.rs:96-145`）
- `app_cache_dir/thumbnails` — 缩略图缓存（`file_management.rs:47-57`）
- `<image>.rrdata`（JSON）— 调整/评分/标签/EXIF 的 sidecar（`exif_processing.rs:1559-1580`）

> 对比桌面：桌面直接用系统对话框的真实文件系统路径，sidecar `<name>.rrdata` 写在源文件同目录（`parse_virtual_path` 返回 `source_path` + `<name>.rrdata`，`file_management.rs:387-417`）。Android 因源被复制进 `.library`，sidecar 落在 `.library` 内同一目录。源文件（无论原 SAF 源还是导入副本）**均不被编辑动作触碰**。

## 3. 显影修改模型：非破坏性（第三种）

RapidRAW 是**非破坏性编辑**：存储「修改工作流的 metadata（调整参数）」，按需重渲染，并缓存渲染结果，**从不就地修改源文件**。

### 3.1 调整参数以 metadata 形式存储（sidecar / 库）

- 每张图的调整存为 `ImageMetadata`（含 `adjustments`、`rating`、`tags`、`exif`），序列化为 `<name>.rrdata` JSON：

```1559:1580:src-tauri/src/exif_processing.rs
pub fn get_primary_sidecar_path(image_path: &Path) -> PathBuf {
    let mut filename = image_path.file_name()...;
    filename.push(".rrdata");                       // <image>.rrdata
    image_path.with_file_name(filename)
}
fn save_primary_metadata(image_path: &Path, metadata: &ImageMetadata) -> std::io::Result<()> {
    let json = serde_json::to_string_pretty(metadata)...;
    fs::write(&primary, json)                       // 写 sidecar，不改源
}
```

- 读取时 `load_sidecar` 解析该 JSON（`exif_processing.rs:215-240`）；`resolve_image_metadata` 用 `is_image_edited` 判断「是否已编辑」（对比 adjustments 是否非默认，`file_management.rs:84-116`）。
- 另有 XMP 同步开关（`enable_xmp_sync`）：可把元数据回写到 XMP sidecar（`file_management.rs:97-104`），但默认关闭，且仍属「另写 sidecar」而非改源像素。

### 3.2 每次渲染重算 + 多级缓存（不碰源）

渲染入口 `generate_transformed_preview` 从源（文件或 `content://`）重新解码并套用 adjustments，但用 hash 缓存全分辨率与几何结果与预览：

```151:194:src-tauri/src/lib.rs
pub fn generate_transformed_preview(...) -> Result<(DynamicImage, f32, (f32, f32)), String> {
    let transform_hash = calculate_transform_hash(adjustments);
    let (transformed_full_res, unscaled_crop_offset) = {
        let mut cache_lock = state.full_transformed_cache.lock()...;   // 全分辨率结果缓存（内存，hash 命中复用）
        ...
    };
    ...
    Ok((final_preview_base, scale_for_gpu, unscaled_crop_offset))
}
```

两级内存缓存：`patched_warped_cache`（几何/镜头/AI 补丁）、`full_transformed_cache`（全部调整），均以 adjustments 的 hash 为键（`lib.rs:159-231`）。
磁盘侧缩略图按 `blake3(path, mtime, adjustments)` 缓存，避免重复整图重算（`file_management.rs:66-82`）。

### 3.3 导出写新文件，不覆盖源

导出/保存结果通过 §1.3 的 MediaStore 路径（Android）或普通文件写出（桌面）生成**新文件**，源（无论外部原文件还是 `.library` 内导入副本）保持不动。

## 与 FotLab 的关系 / 备注

1. **Android 导入=复制** 是关键设计选择：用「导入即复制进 `.library`」规避了 scoped storage 下长期持有 `content://` 权限的复杂度。若 FotLab 也要在 Android 管理外部 RAW，可直接借鉴此模式。
2. **非破坏性模型** 与 dnglab/RawTherapee 一致：源不变、调整存 metadata（RapidRAW 用私有 `.rrdata` JSON，而非 XMP/DNG 内嵌），渲染时重算 + 缓存。与我们 `FOTLAB-FOTRAW-000001`（`FotRaw` 中间表示 + 非破坏性）方向契合。
3. **元数据格式差异**：RapidRAW 用私有 `.rrdata`（JSON），而 dnglab/RAW 生态偏向 XMP/DNG。若要在 FotLab 与 RapidRAW 间互通编辑，需注意格式映射（RapidRAW 也支持 XMP 同步但默认关）。
4. **缓存策略**：内存 hash 缓存 + 磁盘缩略图 hash 缓存是性能关键，Android 上 `.lut_cache` 也用 blake3(uri) 命名——可作为我们缓存键设计的参考。

## 关键文件索引

| 关注点 | 文件:行 |
| --- | --- |
| Android library 根（GetOrCreateInternalLibraryRoot） | `src/hooks/useAppNavigation.ts:511-557`；`src-tauri/src/file_management.rs:3122-3147` |
| Android 库目录 = `getExternalMediaDirs()[0]/.library` | `src-tauri/src/android_integration.rs:580-627` |
| 目录树遍历（普通 fs） | `src-tauri/src/file_management.rs:1227-1346` (`get_folder_tree`/`get_folder_children`) |
| content:// 判定 / 取文件名 / 读字节 | `src-tauri/src/android_integration.rs:66-68,199-305,308-368` |
| 导入：content:// 读字节并 `fs::write` 复制进库（跳过删源） | `src-tauri/src/file_management.rs:3735-3791` |
| LUT 走 content:// | `src-tauri/src/lut_processing.rs:132,159-171,375-385`；`src/hooks/useEditorActions.ts:137-139` |
| 导出写 MediaStore（Pictures/Download/RapidRaw） | `src-tauri/src/android_integration.rs:442-577` |
| AndroidManifest 仅 grantUriPermissions，无持久 URI 权限 | `src-tauri/gen/android/app/src/main/AndroidManifest.xml:31` |
| 虚拟路径解析 + sidecar 命名 `<name>.rrdata` | `src-tauri/src/file_management.rs:387-417` |
| 调整 metadata sidecar 读写（JSON，不改源） | `src-tauri/src/exif_processing.rs:1559-1580,215-240` |
| 已编辑判定 / 元数据加载 | `src-tauri/src/file_management.rs:84-116` |
| 渲染重算 + 内存 hash 缓存 | `src-tauri/src/lib.rs:151-231` |
| 磁盘缩略图 hash 缓存 | `src-tauri/src/file_management.rs:47-82,66-82` |
| LUT 缓存路径（blake3(uri)） | `src-tauri/src/android_integration.rs:96-145` |
