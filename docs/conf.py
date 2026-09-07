# Copyright (C) 2026 fot-lab
#
# This file is part of FotLab. FotLab is free software: you can redistribute
# it and/or modify it under the terms of the GNU General Public License as
# published by the Free Software Foundation, either version 3 of the License,
# or (at your option) any later version. See LICENSE.md in the repository root.

# FotLab 文档构建配置。
# 参考：https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- 项目信息 ---------------------------------------------------------------

project = "FotLab"
author = "fot-lab"
copyright = "2026, fot-lab"
release = "0.1.0"
version = "0.1.0"

# -- 通用配置 ---------------------------------------------------------------

extensions = [
    # 允许使用 Markdown（MyST）编写文档页面
    "myst_parser",
]

# 需要为代码生成 API 文档时，在此追加 "sphinx.ext.autodoc" 等扩展

source_suffix = {
    ".rst": "restructuredtext",
    ".md": "markdown",
}

myst_enable_extensions = [
    "colon_fence",
    "deflist",
]

exclude_patterns = [
    "_build",
    "Thumbs.db",
    ".DS_Store",
]

language = "zh_CN"

# -- HTML 输出配置 ----------------------------------------------------------

html_theme = "sphinx_rtd_theme"
html_static_path = ["_static"]
html_title = f"{project} {release}"
