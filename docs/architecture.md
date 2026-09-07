# 架构设计

FotLab 的核心复杂度不在 UI，而在**如何把两个语言、运行时、构建体系完全不同的上游项目稳定地接入 Android**。因此架构上明确引入一层 **harness（适配层）**。

## 分层原则

harness 的本质是边界层：上游实现可以替换，对外契约保持不变。目录划分围绕"边界"而非"功能"。

```
host (Android / Kotlin)
        │  稳定的 API 契约
        ▼
   harness 适配层
        │  FFI / 进程调用
        ▼
   external/ 上游实现
```

## harness 的目录结构

```
harness/
├── 构建入口（CMakeLists.txt / Cargo.toml / build.gradle）
├── include/           # 对外导出的头文件与 API 声明（契约的机器可读形式）
├── src/
│   ├── lib            # 唯一对外出口，只做转发，不承载业务逻辑
│   ├── api/           # 契约实现：C ABI / JNI，参数校验与错误码映射
│   ├── ffi/           # 所有 unsafe 代码集中于此
│   ├── shim/          # 补齐上游假设的运行环境（allocator、fs、locale、线程）
│   └── host/          # 宿主回调：日志、进度、取消、权限申请
├── external/          # 上游源码（submodule，pin 到具体 commit）
├── testdata/          # 最小可复现样例，体积必须小
├── tests/             # 契约测试与 golden 回归
├── scripts/           # 交叉编译、符号裁剪、产物校验
└── docs/              # 版本对应表与上游 patch 清单
```

## 硬性原则

- **单一入口**：宿主只依赖 `src/lib` 暴露的函数，上游 crate 与脚本对宿主不可见。
- **unsafe 隔离**：`ffi/` 是唯一允许 `unsafe` 的位置，且需说明其安全性依据。
- **宿主回调而非全局状态**：进度、取消、日志通过 `host/` 传入的回调传递。Android 的 `Activity` 可能随时重建，harness 不应持有全局句柄。
- **错误语义统一**：上游的错误码或异常统一翻译为自有错误枚举，附带可诊断上下文，不把上游的裸字符串抛给宿主。
- **可测试性优先**：`testdata/` 存放真实的小体积 RAW / EXIF 样例，测试以 golden 比对方式运行；上游升级时先跑该测试集再决定是否 bump。
- **上游不可改**：对 `external/` 的任何修改必须记录到 patch 清单，否则 submodule 更新后改动会全部丢失。

## 两个上游的差异

| 上游 | 形态 | 接入要点 |
| --- | --- | --- |
| dnglab | Rust workspace，`rawler` 是可直接依赖的 library crate，`bin/` 只是 CLI | 作为静态库链接，需处理交叉编译 target 与 Android NDK 的 ABI 对齐 |
| exiftool | Perl 脚本 + `lib/` 模块，无库形态 | 需要在 Android 上提供 Perl 运行时，或改为进程/服务方式调用，并处理启动开销与生命周期 |

> **注意**
> exiftool 的接入方案尚未定稿，需要在"内置 Perl 运行时"与"替代实现"之间评估。
