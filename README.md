# GPT Audio Dictation

**面向 ChatGPT 网页端的音频转录、自动分段与本地归档扩展。**

[![checks](https://github.com/ayinyu666-lgtm/gpt-audio-dictation/actions/workflows/ci.yml/badge.svg)](https://github.com/ayinyu666-lgtm/gpt-audio-dictation/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<img src="extension/icons/icon128.png" width="88" alt="GPT Audio Dictation icon">

它驱动 ChatGPT 网页自身的听写功能，把长音频自动拆成多个片段，继续下一段，并在本地归档模块开启时保存“本段原始转录 + 紧随其后的 GPT 回答”。它并不只用于课堂或翻译：讲座、访谈、会议记录、口述笔记、语言学习和其他需要连续转录的场景都可以使用。

> 本项目不是 OpenAI 官方产品，与 OpenAI 没有隶属或背书关系。ChatGPT 网页结构和按钮文案发生变化时，可能需要更新页面适配层。

## 能做什么

- 跟踪 ChatGPT 正在使用的麦克风流，不另开录音设备。
- 根据说话与停顿自动结束当前段，并继续录下一段。
- 适配浏览器把后台标签页定时器降频的情况。
- 中文、Deutsch、English 三套首次使用引导。
- 每次保存只读取对应的用户转录和紧随其后的 GPT 回答，不把积压段落全部写成最后一条回答。
- 分类在段落开始时冻结；中途切换分类不会把旧段写进新目录。
- 保存任务按 `saveId` 独立持久化，支持刷新、关闭标签页、扩展重载和服务暂时离线。
- 临时错误自动退避重试；不可自动解决的任务保留为失败状态，可在面板处理。
- Windows 原生目录选择、可写探针、配置备份与恢复。
- 本地 Markdown 写入使用内容摘要、重复检测、半段恢复和冲突保护。

## 它不负责什么

- 不提供语音识别模型，转录由已登录的 ChatGPT 网页完成。
- 不需要 OpenAI API Key，也不会把内容发送到本项目自己的服务器。
- 不保证绕过 ChatGPT 的账号、地区、额度或功能限制。
- 不自动修改 ChatGPT 的账号记忆；可选的提示词仍需要用户自己复制和发送。

## 典型用途

| 场景 | 做法 |
|---|---|
| 讲座 / 课程 | 自动分段转录，按主题归档到 Markdown |
| 访谈 / 会议 | 保存原始转录和后续整理结果，便于复查 |
| 个人口述笔记 | 连续说，自动续录并归档到指定目录 |
| 语言学习 | 要求 ChatGPT 翻译、解释或整理为目标语言 |
| 调研记录 | 在面板自定义提示词，让回答以摘要、术语表或其他格式呈现 |

## 系统要求

- Windows 11 优先；其他 Windows 版本可按相同方式尝试。
- Microsoft Edge 或其他 Chromium 浏览器。
- Node.js 22 或更高版本，仅在启用本地归档时需要。
- 一个可正常使用网页听写功能的 ChatGPT 账号。

## 快速安装

### 1. 下载并解压

从 GitHub Releases 下载 Windows 包，解压到普通本地文件夹。不要直接在压缩包预览窗口中运行。

### 2. 加载扩展

1. 双击 `install.bat`。
2. Edge 会打开扩展管理页。
3. 开启“开发人员模式”。
4. 选择“加载解压缩的扩展”。
5. 选择项目中的 `extension` 文件夹。
6. 打开或刷新 ChatGPT 页面。

Chromium 要求用户手动加载未上架商店的扩展，这是浏览器安全限制。

### 3. 启动本地归档服务

双击：

```text
writer\start-writer.bat
```

服务只监听：

```text
http://127.0.0.1:8799
```

### 4. 首次使用

1. 在浮动面板中打开“归档 GPT 回答”。
2. 点击“选择文件夹”。
3. 新建或选择一个分类。
4. 先用“快速测试”完成一段短测试。
5. 确认转录、继续录音和本地文件都正常后，切换“正式模式”。

## 本地归档格式

默认目录结构：

```text
选择的根目录\
  分类名\
    2026-10-17 周六 17-05.md
```

默认文件内容：

```md
# 分类名

- 日期：2026-10-17（周六）
- 开始时间：17:05:12
- 会话：https://chatgpt.com/c/...
- 来源：GPT Audio Dictation（ChatGPT 网页音频转录与本地归档）

## 17:05:18　第 1 段

**原始转录**

...语音转录...

**GPT 回答 / 处理结果**

...ChatGPT 输出...
```

文件名模板和是否写入原始转录、GPT 回答可在 `writer/config.json` 中调整。公开示例见 [`writer/config.example.json`](writer/config.example.json)。

## 工作方式

```text
ChatGPT 页面
  -> page-hook.js：麦克风电平、分段、页面按钮、回答配对
  -> panel.js：面板、设置、分类、持久化保存队列
  -> background.js：扩展后台与 loopback 转发
  -> writer.mjs：校验、去重和本地 Markdown 写入
```

更详细的结构说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 隐私与安全

- 只有打开“归档 GPT 回答”时，扩展才读取已发送的用户转录及其后的 GPT 回答。
- 保存内容先进入浏览器扩展的本地队列，再通过 `127.0.0.1` 交给本地 writer。
- writer 不会连接外部服务器。
- 生成的 Markdown、日志、浏览器配置和个人目录路径默认被 `.gitignore` 排除。
- 不要公开上传真实转录、账号 Cookie、浏览器配置或包含私人路径的日志。

安全问题请按 [`SECURITY.md`](SECURITY.md) 处理。

## 更新后重启 writer

Node.js 会在启动时读取 `writer.mjs`。如果你更新了项目但 writer 已经运行，旧进程仍会继续使用旧代码。

先关闭原来的 writer 窗口，再重新双击：

```text
writer\start-writer.bat
```

若端口 `8799` 已被现有 writer 占用，重复启动会显示“本服务可能已经在运行”，窗口会停住等待你阅读提示。

## 常见问题

### 面板显示 writer 离线

1. 双击 `writer\start-writer.bat`。
2. 打开 `http://127.0.0.1:8799/ping`，应看到 `ok: true`。
3. 如果仍有旧 writer 在后台运行，结束监听 `8799` 的 Node 进程后重新启动。

### 文件夹选择器没有出现

直接双击 `choose-save-folder.bat`，或在面板中点击“选择文件夹”。

### 找不到 ChatGPT 按钮

先刷新 ChatGPT。若页面刚改版，需要更新 `extension/page-hook.js` 中的按钮文案或页面结构适配。

### 回答没有归档

查看面板中的保存状态：

- “待保存”：队列还在等待回答或 writer。
- “落盘失败”：任务已保留，修复目录、分类或文件权限后重试。
- 缺少分类：在“失败段落分类”中明确选择，扩展不会用当前分类猜测旧任务的去向。

## 开发与检查

```powershell
npm run check
```

本地构建发布包：

```powershell
npm run pack
```

构建结果写入 `dist/`：

- 完整 Windows 包
- 仅扩展安装包

## 配置

配置示例：

```powershell
Copy-Item writer\config.example.json writer\config.json
```

首次选择目录时，writer 也会自动创建 `config.json`。不要把这个文件提交到仓库。

## 版本

当前版本：`1.2.0`

变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。

## 参与贡献

见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## License

[MIT](LICENSE)
