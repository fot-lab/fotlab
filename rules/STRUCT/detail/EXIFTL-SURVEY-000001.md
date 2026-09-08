# 调研 — external/exiftool 模块（ExifTool 第三方库）

- ID: EXIFTL-SURVEY-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-STRUCT-000001`（单模块源码布局；`external/` 为第三方库存放区）

## Background & Goal

`external/exiftool/` 是从上游 [exiftool.org](https://exiftool.org/) 引入的第三方 Perl 库（作者 Phil Harvey，
版本 **13.59**，最新生产版本为 13.55；其余为开发版本）。本调研目标是厘清该模块的目录结构、核心代码组织方式与
功能范围，为后续在 FotLab 项目中评估/集成图像元数据（EXIF/IPTC/XMP/GPS 等）读写能力提供依据。

> 现状：全仓代码中（除 `external/` 本身外）目前**没有任何**对其他模块引用 `exiftool` / `Image::ExifTool` 的
> 代码（`app/`、`build.gradle.kts`、文档中均无引用）。即本模块当前为「存储待评估」状态，尚未接入构建或运行时。

## 调研范围

- 路径：`external/exiftool/`
- 形态：`Image::ExifTool` Perl 模块集合 + 命令行应用（`exiftool` 脚本）
- 许可：Perl Artistic License 或 GPL（与 Perl 本身同条款），Copyright 2003–2026 Phil Harvey
- 运行依赖：Perl 5.004+；可选模块 Archive::Zip、Compress::Zlib、Digest::MD5/SHA、POSIX::strptime 等用于
  解码压缩/加密数据、计算摘要

## 目录结构（代码结构）

```
external/exiftool/
├── exiftool                  # 命令行主程序（Perl 脚本，读/写元信息入口）
├── windows_exiftool         # Windows EXE 捆绑版对应的应用脚本（同 exiftool，去掉符号链接处理）
├── Makefile.PL              # CPAN 安装 Makefile（NAME => Image::ExifTool）
├── MANIFEST / META.json / META.yml   # 分发文件清单与依赖声明
├── Changes                  # 修订历史（由 html/history.html 生成）
├── README                   # 总览与运行/安装说明
├── LICENSE
├── perl-Image-ExifTool.spec # Red Hat RPM 打包规格
├── pp_build_exe.args / validate / build_geolocation / build_tag_lookup  # 构建/校验辅助脚本
├── arg_files/   (11 .args)  # 元数据格式互转的参数文件（EXIF↔IPTC↔XMP↔GPS↔PDF）
├── config_files/(21 .config)# 用户自定义配置样例（新增标签、区域转换、GPS/UTM 计算等）
├── fmt_files/   (4  .fmt)   # -p 打印格式样例（生成 GPX/KML 轨迹）
├── html/        (202 文件)  # 完整 HTML 文档（含 TagNames/ 155 个标签名文档）
├── lib/                      # 核心 Perl 库（见下）
└── t/           (759 文件)  # 验证测试（逐个格式的 .t 脚本 + 参考答案 .out + 测试图 .jpg/.xmp 等）
```

### lib/ 核心库布局（`lib/Image/ExifTool/`）

| 文件/目录 | 作用 |
| --- | --- |
| `ExifTool.pm` | **核心引擎**。定义 `package Image::ExifTool`，提供 `ImageInfo` 等公共 API（`Public`/`DataAccess`/`Utils`/`Vars` 导出标签），维护 `$VERSION='13.59'`、`@loadAllTables`（主标签表加载顺序）、字节序、`%allTables` 等全局状态 |
| `Writer.pl` | **写入例程**（`package Image::ExifTool` 的同包续文件）。`SetNewValue`/`WriteInfo` 等写接口，含各格式写目录的 `%tiffMap` 路线图，多数写函数在首次调用时 autoload |
| `TagLookup.pm` / `BuildTagLookup.pm` | 标签名→标签信息查表与查表生成 |
| `TagNames.pod` | 标签名文档源 |
| `README` | **开发者参考资料**：标签表（tag table）与标签信息散列（tagInfo hash）的完整格式规范（28 个 TABLE 级特殊键 + 标签级字段如 `Name`/`Format`/`Count`/`Flags`/`ValueConv`/`PrintConv`/`Condition` 等） |
| `*.pm`（约 225 个） | **格式/厂商模块**，每个定义一组标签表与处理过程，例如：`Exif.pm`、`GPS.pm`、`IPTC.pm`、`XMP.pm`、`JPEG.pm`、`TIFF`/`BigTIFF.pm`、`PNG.pm`、`QuickTime.pm`、`PDF.pm`、`Photoshop.pm`、`MakerNotes.pm`、`Canon*.pm`、`Nikon*.pm`、`Sony*.pm`、`DNG.pm`、`Geotag.pm`、`Geolocation.pm` 等 |
| `Write*.pl`（10 个） | 特定格式的写实现：`WriteExif.pl`、`WriteIPTC.pl`、`WriteXMP.pl`、`WritePDF.pl`、`WritePNG.pl`、`WritePhotoshop.pl`、`WritePostScript.pl`、`WriteQuickTime.pl`、`WriteRIFF.pl`、`WriteCanonRaw.pl` |
| `Charset/`（33 .pm） | 字符集编解码（Arabic、Cyrillic、JIS、ShiftJIS、Mac*/PDFDoc 等） |
| `Lang/`（18 .pm） | 本地化标签名（cs/de/es/fr/it/ja/ko/zh_cn/zh_tw 等） |
| `Geolocation.dat` | 二进制地理定位数据库（基于 geonames.org，CC 许可），由 `Geolocation.pm` 读取 |
| `MIEUnits.pod` / `Shift.pl` / `Import.pl` / `QuickTimeStream.pl` | 辅助/协议定义文件 |

## 核心架构与机制

### 1. 引擎 + 模块表（table-driven）设计
`ExifTool.pm` 是调度中心，自身不硬编码具体标签，而是通过 `@loadAllTables` 按顺序加载各格式模块。每个模块是一个
标签表（tag table）：以 tag ID 为键、标签信息散列为值。引擎据此对任意文件做「探测文件类型 → 选择处理过程 →
查表解析/写入」。

### 2. 标签表（tag table）规范（详见 `lib/Image/ExifTool/README`）
- **TABLE 级特殊键**（全大写，避免与标签冲突）：如 `PROCESS_PROC`（解析进程，默认 `Exif::ProcessExif` 或
  `QuickTime::ProcessMOV`）、`WRITE_PROC`、`CHECK_PROC`、`GROUPS`、`FORMAT`、`WRITABLE`、`NAMESPACE`（XMP）、
  `PRIORITY`/`AVOID`/`PREFERRED`（写优先级）、`PERMANENT`（不可删，maker note 默认）等共 28 个。
- **标签级字段**：`Name`/`Description`/`Groups`/`Format`/`Count`/`Flags`/`ValueConv`/`ValueConvInv`/`PrintConv`/
  `Condition`/`SubDirectory`/`RawConv`/`DataMember` 等。`Flags` 含 `Binary`/`Avoid`/`Hidden`/`Permanent`/
  `List`/`Flat`/`Lang` 等数十种。
- 标签信息可为：标量（仅标签名）、散列引用、或带 `Condition` 的散列列表（按条件选表）。

### 3. 读写流程
- **读**：`ImageInfo($file)` → 探测文件类型 → 调用对应模块的 `PROCESS_PROC` → 按表解码二进制 → `ValueConv`（原始值转
  可读值）→ `PrintConv`（数值转可读文本）。输出可按 Group（EXIF/IPTC/XMP/GPS…）分组。
- **写**：`SetNewValue` 设值 → `WriteInfo` 调用 `WRITE_PROC` 重建目录 → `CHECK_PROC` 校验 → 写回；支持
  `-tagsFromFile` 跨文件/跨格式拷贝。

### 4. 多语言与字符集
`Lang/*.pm` 提供 18 种语言的标签名；`Charset/*.pm` 提供 33 种字符集编解码，保证多语言文本与非常规编码正确显示。

## 功能范围（要点）

- **文件类型**：README 列出约 360 种文件类型（r/w/c 标记读/写/建），覆盖 RAW（CR2/CR3/ARW/NEF/DNG/ORF/RW2…）、
  主流图像（JPEG/TIFF/PNG/WebP/HEIC/AVIF/JXL/GIF）、视频（MP4/MOV/MKV/AVI/RM/WTV…）、文档（PDF/XPS/DOCX/PPTX/
  XLSX/EPUB/HTML）、音频（MP3/FLAC/OGG/WAV…）、及压缩包/元数据包等。
- **元信息格式**：EXIF、GPS、IPTC、XMP、MakerNotes（Canon/Nikon/Sony/Panasonic/Pentax/… 数十厂商）、Photoshop IRB、
  ICC Profile、MIE、JFIF、Ducky APP12、PDF、PNG、Canon VRD、Nikon Capture、GeoTIFF、DICOM、ID3、Matroska、MXF 等。
- **命令行能力**：提取/写入、批量重命名（`-filename`）、CSV/JSON 导入导出、地理标记（`-geotag`）、地理定位
  （`Geolocation`）、`-p` 自定义打印格式、配置文件扩展（`-config`）、`-stay_open`（服务端常驻模式）等。

## 辅助文件

- **arg_files/（11 个）**：格式互转参数文件。如 `exif2xmp.args`、`iptc2exif.args`、`gps2xmp.args`、`xmp2pdf.args`、
  `iptcCore.args`。用法：`exiftool -tagsFromFile SRC -@ exif2xmp.args DST`，以 `<` 映射源/目标标签。
- **config_files/（21 个）**：用户自定义样例。如 `example.config`（新增 EXIF/IPTC/XMP/PNG/MIE/Composite 标签与
  快捷方式）、`gps2utm.config`（由 GPS 生成 UTM 坐标）、`picasa_faces.config`/`convert_regions.config`（人脸/
  区域转换）、`time_zone.config`/`local_time.config`（由 EXIF/GPS 推算时区）等。
- **fmt_files/（4 个）**：`-p` 打印格式样例，将 GPS 轨迹输出为 GPX（`gpx.fmt`/`gpx_wpt.fmt`）或 KML
  （`kml.fmt`/`kml_track.fmt`）。

## 文档与测试

- **html/（202 文件）**：完整文档。`index.html` 总览，`ExifTool.html` API 文档，`TagNames/`（155 个 `.html`）逐格式
  标签名文档，`history.html` 修订史，`geotag.html`/`geolocation.html`/`struct.html`/`writing.html`/`faq.html` 等专题。
- **t/（759 文件）**：回归测试。每个格式一个 `.t` 脚本 + 对应 `.out` 期望输出 + 测试样本（`.jpg`/`.xmp`/…）。
  覆盖 AAC/Canon/DNG/EXIF/IPTC/Geotag/QuickTime 等几乎所有模块。

## 与本项目（FotLab）的关系

- `external/` 是第三方库存放区；`exiftool` 作为独立 Perl 工具链引入。
- **当前未集成**：仓库内无调用方，Android 应用（`app/`，Kotlin）无法直接 `use Image::ExifTool`。若需使用，典型路径见
  下文「在 Android 上运行 Perl / ExifTool 的可行方案（调研补充）」一节：
  1. （推荐）交叉编译 Perl（perl-cross + NDK）随 APK 以 `.so` 分发，并以 `ProcessBuilder` 调用 `exiftool`（方案 A）；
  2. 简化版：交叉编译 perl + exiftool 解压到 `files` 目录并赋权执行（方案 B，仅 32 位 PIE，API 21+）；
  3. 改用原生/JVM 元数据库（如 Apache Commons Imaging、Sanselan、camera2 ExifInterface）替代；
  4. 在桌面/服务端辅助流程中调用。
- 决策要点：许可证兼容（Artistic/GPL 与本项目 `LICENSE.md` 是否冲突需确认）；Perl 运行时在移动端不可行，需权衡
  引入体积与跨平台成本。

## 在 Android 上运行 Perl / ExifTool 的可行方案（调研补充）

ExifTool 是纯 Perl 程序，Android（ART/Dalvik，Kotlin/Java）无内置 Perl 运行时。要在 FotLab 中复用 ExifTool，
需自行提供 Perl 运行时。经调研（2026-09-08）现实中已有完整可落地的方案，归纳为四类：

### 方案 A — 交叉编译 Perl（perl-cross + NDK），随 APK 分发并以进程调用（推荐 / 主流）
基于 `bestvibes/exiftoolwrapper-android`（MIT，2026，GitHub Actions 可复现构建）的成熟实践：
- **交叉编译**：用 `perl-cross`（github.com/arsv/perl-cross，2025 仍活跃）在启用 `DynaLoader` 的配置下交叉编译 Perl，
  `make install` 到暂存树；`native/PINS` 精确锁定 perl / ExifTool / perl-cross / NDK 版本与 SHA256。
- **打包**：将 perl 解释器重命名为 `libperl.so`、各 XS 模块（POSIX、Compress::Raw::Zlib 等）重命名为
  `libperl_xs_<flat_name>.so`，经 `jniLibs` 按 ABI 随应用安装分发（落入 `nativeLibraryDir`，无需 `chmod`）；
  ExifTool 脚本 + `Image::ExifTool/` 库树 + 纯 Perl `@INC` 打包为 `assets/perl5.tar`，首次启动经 `AssetExtractor`
  解压到 `filesDir/perl5/`。
- **调用**：`ProcessBuilder(libperl.so, "-I", arch, "-I", lib, exiftool, …)` —— **argv 列表、无 shell、无字符串插值**；
  SAF 返回的 URI 先拷到缓存子目录，exiftool 读写副本后写回源 URI。
- **链接**：运行时在 `filesDir/perl5/arch/auto/<dist>/<dist>.so` 建符号链接指向 `nativeLibraryDir/libperl_xs_*.so`
  （因安装时 `nativeLibraryDir` 随机化，每次启动重建），使 `DynaLoader` 能在规范 archlib 路径找到 XS 模块。
- **ABI**：`arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`，提供 `universal` APK。
- **安全**：过滤危险 flag（`-config`、`-@`、`-stay_open`、`-execute*`）防止加载任意 Perl；命令写入 `command_history`。

### 方案 B — 简化打包（交叉编译 perl + exiftool 解压到 files 目录）
基于 `vdzhos-dh/ExifToolForAndroid`（ExifDateFixer，方案 A 的派生）：
- 将 exiftool 与**交叉编译的 Perl**（arm + x86，仅 PIE）打包进 APK，运行时解压到
  `/data/data/<pkg>/files` 并设置可执行权限，再运行 exiftool。
- ABI 仅 32 位（arm/x86）+ PIE，隐含要求 **Android 5.0（API 21）及以上**；不支持 64 位 ABI（除非系统兼容 32 位）。

### 方案 C — Termux / 原生构建（仅适合调试或依赖外部环境）
- **Termux**（Play Store）预装交叉编译好的 perl，可在 Android 上原生编译 Perl 5.30+；但作为 App 内嵌依赖体积大、
  且需用户安装 Termux，不适合产品化。
- **CCTools** 等非官方工具链已停更、路径随应用而异，不推荐。
- **SL4A** 已废弃，不再适用。

### 方案 D — JNI 内嵌 Perl 解释器
- 通过 JNI 将 perl 解释器嵌入 native 库，在 JVM 内直接调用。复杂度高，仅在需要进程内深度集成时考虑；
  方案 A 的「perl 作为独立 `.so` + `ProcessBuilder` 调用」已能满足绝大多数元数据读写场景。

### 官方文档与约束（perlandroid）
- `perldoc.perl.org/perlandroid` 给出：① 用 NDK 独立工具链（`make-standalone-toolchain.sh`）交叉编译，需**类 Unix
  主机**（Windows 原生不支持该流程），早期目标 ABI 为 `arm-linux-androideabi` / `mips` / `x86`；② 或 Termux/CCTools
  原生构建。
- 限制：交叉编译仅正式支持类 Unix 主机；旧设备/低权限受限；要求 Android 2.0+。

### 体积与许可影响
- **体积**：perl 解释器 + ExifTool `lib/` 约数十 MB（按 ABI 拆分后 `universal` 包更大），需在「功能完整性」与「包体」
  间权衡；可裁剪 `lib/` 中无关厂商模块。
- **许可**：ExifTool 与 perl 均为 Artistic/GPL；作为独立进程调用（方案 A/B）属「聚合分发」，通常不触发 GPL 传染；
  但需确认与本项目 `LICENSE.md` 的兼容，并保留上游版权声明（见 Q2）。

## Constraints

- C1 — 本模块为第三方上游代码，**不应**在库内直接修改；如需定制，应通过 `config_files/` 或派生补丁，并保留上游来源与版本。
- C2 — 引入到 Android 构建前须确认许可（GPL 传染性）与运行时可行性。
- C3 — 文档与代码引用路径一律以仓库根为基准（如 `external/exiftool/lib/Image/ExifTool/README`）。

## Acceptance Criteria（调研交付）

- AC1 — 已刻画 `external/exiftool` 的目录结构与核心代码组织（引擎 `ExifTool.pm` + 模块表 + `Writer.pl`）。
- AC2 — 已说明标签表（tag table）机制与读写流程。
- AC3 — 已列出辅助文件（arg/config/fmt）与文档/测试布局，并指出当前未与 `app/` 集成。

## Open Questions

- Q1 — 是否计划将 ExifTool 接入 FotLab？若接入，采用独立进程调用还是替换为 JVM 原生库？**TBD**
- Q2 — 引入 GPL 许可组件对 `LICENSE.md` 的影响评估。**TBD**
- Q3 — 是否需要裁剪 `lib/` 中无关厂商模块以减小体积（若走进程调用路线）？**TBD**

## Change History

- 2026-09-08 — 初始调研稿。梳理 `external/exiftool`（ExifTool 13.59）的目录结构、核心架构（引擎 + 标签表 +
  写入例程）、功能范围（360+ 文件类型、EXIF/IPTC/XMP/GPS/厂商 MakerNotes 等）、辅助文件（arg/config/fmt）、
  文档与测试布局，并指出当前仓库未与之集成及潜在集成路径与许可约束。
- 2026-09-08 — 补充「在 Android 上运行 Perl / ExifTool 的可行方案」：基于 `bestvibes/exiftoolwrapper-android`
  （perl-cross + NDK 交叉编译 perl 为 `.so`、`ProcessBuilder` 调用，覆盖 4 种 ABI）、`vdzhos-dh/ExifToolForAndroid`
  （简化解压到 files 目录，32 位 PIE/API 21+）、Termux/CCTools/SL4A、JNI 内嵌四类方案，并引用 perlandroid 官方
  交叉编译约束（需类 Unix 主机、早期 ABI）；补充体积与 GPL 许可影响。同步将「与本项目关系」集成路径指向该节。
