# 快速开始

本文档描述获取源码与准备开发环境的流程。

## 获取源码

`external/` 下的第三方模块以 git submodule 方式引入，克隆时必须带上子模块：

```bash
git clone --recurse-submodules https://github.com/fot-lab/fotlab.git
cd fotlab
```

若已克隆但缺少子模块内容，执行：

```bash
git submodule update --init --recursive
```

## 环境要求

| 组件 | 说明 |
| --- | --- |
| Android Studio | 应用开发与调试 |
| Android NDK | 编译 JNI 桥接层 |
| Rust toolchain | 构建 `external/dnglab`（`rawler` 等 crate） |
| CMake | 原生构建编排 |
| Perl | 运行 `external/exiftool` |
| C++17 编译器（NDK clang） | 构建 `external/RawTherapee`（`rtengine`） |
| Python 3.x | 仅用于离线运行 `external/colour` 生成/校验色彩数据，不进入 APK |

> **注意**
> 具体版本要求与构建命令尚未确定，本节将在首个可构建提交后补齐。
> 在此之前，请勿依据本节内容配置 CI。

## 目录导览

| 路径 | 内容 |
| --- | --- |
| `external/dnglab` | Rust 实现的 DNG 处理工具链（workspace：`bin`、`rawler`、`embedftp`） |
| `external/exiftool` | Perl 实现的元数据处理工具（`lib/` + `exiftool` 入口脚本） |
| `external/RawTherapee` | C++ 实现的 RAW 处理引擎（`rtengine/` + `rtdata/` 配置数据） |
| `external/colour` | Python 色彩科学库（`colour/` 包 + 内置色彩数据），构建期参考用 |
| `docs/` | 本文档站点源 |

## 下一步

- 阅读[架构设计](architecture.md)了解模块的边界与分层
- 阅读[外部依赖与许可](external/index.md)了解子模块的使用约束
