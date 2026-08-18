# 扩展工具说明

`tool-extension/` 目录用于存放**扩展脚本工具**，放置在此目录下的 `.yaml` / `.yml` 文件会被自动扫描并注册为可调用工具。

## 快速开始

在 `tool-extension/` 下创建一个 `.yaml` 文件，例如 `hello.yaml`：

```yaml
name: hello
description: 打个招呼
type: powershell
parameters:
  - name: who
    type: string
    description: 要打招呼的对象
    required: false
script: |
  $who = "{{who}}"
  if (-not $who) { $who = "World" }
  Write-Host "Hello, $who!"
```

无需重启服务，脚本会在下次调用时自动被发现。

## 文件格式

### 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | **是** | 脚本唯一名称，AI 通过此名称调用脚本 |
| `description` | string | 否 | 一句话描述脚本功能，会注入到系统提示词中 |
| `type` | string | 否 | 脚本类型，默认 `cmd`。可选：`cmd`、`powershell`、`python`、`bash` |
| `parameters` | array | 否 | 脚本参数列表 |
| `script` | string | **是** | 脚本内容，使用 YAML 字面量块标量（`\|`）书写 |

### 参数定义（parameters）

每个参数支持以下字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | **是** | 参数名 |
| `type` | string | 否 | 参数类型，默认 `string` |
| `description` | string | 否 | 参数说明，会注入到系统提示词 |
| `required` | boolean | 否 | 是否必填，默认 `false` |

### 参数占位符

在 `script` 中使用 `{{参数名}}` 引用参数，执行时会自动替换为实际值：

```yaml
script: |
  Write-Host "你好，{{name}}！"
  Get-ChildItem "{{path}}"
```

如果某个 `{{占位符}}` 未被替换（即 AI 调用时未传入对应参数），工具会报错并列出未替换的占位符。可选参数需要在脚本中自行处理默认值：

```yaml
script: |
  $name = "{{name}}"
  if (-not $name) { $name = "默认值" }
```

## 脚本类型

### cmd

Windows 命令提示符，通过 `cmd.exe /c` 执行：

```yaml
type: cmd
script: |
  dir C:\
  echo 完成
```

### powershell（推荐）

PowerShell，通过 `powershell.exe -NoProfile -ExecutionPolicy Bypass -Command` 执行：

```yaml
type: powershell
script: |
  chcp 65001 > $null
  Get-Process | Select-Object Name, CPU | Format-Table
```

> **建议**：脚本开头加 `chcp 65001 > $null` 设置 UTF-8 编码，避免中文输出乱码。

### python

Python，通过 `python`（Windows）或 `python3`（Linux）执行（需要系统已安装 Python）：

```yaml
type: python
script: |
  import platform
  print(f"系统: {platform.system()} {platform.release()}")
```

### bash

Bash 脚本，通过 `bash` 执行（Windows 需要安装 Git Bash 或 WSL）：

```yaml
type: bash
script: |
  echo "当前用户: $(whoami)"
  echo "工作目录: $(pwd)"
  df -h
```

## 目录结构

```
tool-extension/
├── README.md               # 本说明
├── system/                  # 系统相关脚本
│   └── system-info.yaml     # 获取系统信息
└── your-category/           # 按功能分目录（可选）
    └── your-script.yaml
```

子目录会被递归扫描，可以按功能分类组织脚本。

## 调用方式

AI 通过 `extension_script_tool` 工具调用脚本：

```json
{
  "scriptName": "system-info",
  "arguments": {
    "detail": "full"
  }
}
```

- `scriptName`：脚本的 `name` 字段值
- `arguments`：参数键值对，无参数时可省略

## 执行限制

| 限制 | 默认值 | 说明 |
|------|--------|------|
| 超时时间 | 10 秒 | 超过后强制终止 |
| 安全输出大小 | 8 KB | 超过后拦截返回 |
| 最大输出大小 | 32 KB | 硬限制，不可跳过 |

可在 `settings/tool_settings.json` 中调整这些值。
