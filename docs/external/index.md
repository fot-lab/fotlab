# 外部依赖与许可

`external/` 下的模块是独立的外部项目，以 git submodule 引入，**各自遵循自己的许可证**，本项目不对它们主张任何权利，也不对其许可证条款的准确性、完整性或适用性提供任何担保。使用、修改或再分发这些模块时，请以各模块自身的许可证为准。

## dnglab

- 语言：Rust
- 用途：RAW 解析与 DNG 处理
- 结构：

  | 路径 | 说明 |
  | --- | --- |
  | `rawler` | 核心库 crate，首方 native 集成代码应直接依赖它 |
  | `bin` | 命令行前端，应用侧不需要 |
  | `embedftp` | 附带工具，按需评估 |

- 许可证：见 `external/dnglab/LICENSE`

## exiftool

- 语言：Perl
- 用途：图像元数据读写
- 结构：

  | 路径 | 说明 |
  | --- | --- |
  | `exiftool` | 入口脚本 |
  | `lib/` | Perl 模块，脚本运行必需 |
  | `arg_files/`、`config_files/` | 参数与配置文件，按需裁剪 |
  | `t/`、`html/` | 测试与离线文档，发布产物中可剥离 |

- 许可证：见 `external/exiftool/LICENSE`

## RawTherapee

- 语言：C++（CMake 构建）
- 用途：RAW 处理引擎，集成方式仍在评估
- 结构：

  | 路径 | 说明 |
  | --- | --- |
  | `rtengine/` | 核心图像处理引擎，唯一具备集成价值的目录 |
  | `rtgui/` | 桌面 GUI（GTK），应用侧不需要 |
  | `rtdata/` | ICC/DCP 配置、camconst.json 等运行时数据，按需裁剪 |
  | `licenses/` | 上游随附的第三方许可证文本 |
  | `CMakeLists.txt`、`cmake/`、`win.cmake` | 构建脚本 |

- 许可证：GPL-3.0，见 `external/RawTherapee/LICENSE`（与本项目 `LICENSE.md` 同许可证；`licenses/` 下的随附许可需一并评估）

## colour

- 语言：Python
- 用途：色彩科学算法与数据的参考实现（色空间转换、色彩适应、LUT 相关计算）
- 结构：

  | 路径 | 说明 |
  | --- | --- |
  | `colour/` | Python 包本体，含 `.cube` / `.csp` / `.csv` 等色彩数据 |
  | `utilities/` | 上游工具脚本 |
  | `docs/`、`BIBLIOGRAPHY.bib` | 文档与参考文献，发布产物中可剥离 |
  | `pyproject.toml`、`requirements.txt` | 构建与依赖声明 |

- 许可证：BSD-3-Clause，见 `external/colour/LICENSE`（与本项目 GPL-3.0 兼容，随附义务为保留版权声明）
- 注意：Python 运行环境不存在于 Android 上，本模块不作为运行时依赖，仅作为构建期/离线参考（详见设计项 `FOTLAB-NATIVE-000001` Q8）

## rawloader

- 语言：Rust（crate `rawloader`，edition 2018，v0.37.2）
- 用途：从相机 RAW 格式提取数据的解码库；是 `external/dnglab` 中 `rawler` 的上游原库（同一作者），在 fotlab 中作为参考/对比基线，不直接作为运行时依赖
- 结构：

  | 路径 | 说明 |
  | --- | --- |
  | `src/` | 库源码（38 个 `.rs`） |
  | `data/` | 相机数据库（`*.toml` 定义 + `join.rs` 构建脚本，编译期合并），build = `data/cameras/join.rs` |
  | `examples/`、`fuzz/`、`regressions/` | 示例、模糊测试与回归样本（`regressions/` 含 1100+ 样本），发布产物中可剥离 |
  | `benchmark`、`identify` | 两个二进制示例，应用侧不需要 |

- 许可证：LGPL-2.1，见 `external/rawloader/LICENSE`

## 使用约束

- **pin 到具体 commit**：submodule 必须锁定版本，禁止直接跟踪上游分支的最新提交。
- **不直接修改上游**：若必须打补丁，将补丁与说明记录到仓库中，并在升级时重新应用。
- **裁剪发布内容**：只打包运行必需的目录（`lib/`、`arg_files/` 等），测试与文档目录不进入产物。
- **许可证随附**：分发应用时，需同时随附各外部模块的许可证文本。
