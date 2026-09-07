# 参与贡献

## 文档

文档使用 Sphinx 构建，页面以 Markdown（MyST）编写，入口为 `docs/index.rst`。

本地预览（需要 Python 与 make）：

```bash
pip install -r docs/requirements.txt
cd docs
make html
```

Windows 下可使用 `docs\make.bat html`。产物位于 `docs/_build/html`。

Read the Docs 会在每次推送后自动构建，配置见仓库根目录的 `.readthedocs.yaml`。

### 新增页面

1. 在 `docs/` 下创建 `.md` 文件。
2. 在 `docs/index.rst` 对应的 `toctree` 中登记文件名（不含扩展名）。
3. 未被 toctree 引用的页面会触发构建警告。

### 写作约定

- 一个页面只讲一件事，标题层级从 `#` 开始。
- 命令、路径、配置项使用行内代码。
- 未定稿的内容用 `> **注意**` 引用块显式标注，不要留下看似确定的错误描述。

## 代码

- 提交前确认 `external/` 的 submodule 指针是有意变更的。
- 修改 harness 时同步更新 `docs/architecture.md` 中的分层约定。
- 新增上游补丁时，同步登记到外部依赖与许可页面所述的 patch 清单。
