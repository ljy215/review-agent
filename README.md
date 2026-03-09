# Agent-Review: 基于大语言模型的交互式代码审计与辅助修复 Agent

Agent-Review 是一款深度集成于 IntelliJ IDEA 的 AI 编程助手系统。它打破了传统 AI 助手“只说不做”的局限，通过 Java 插件底层的 PSI (程序结构接口) 与 VFS (虚拟文件系统) 采集上下文，配合 Python 后端的大模型推理能力，实现了从“全项目扫描”到“代码自动热注入”再到“局部可逆回滚”的闭环研发体验。

## 🌟 核心特性

### 1. 全项目上下文感知审查 (Context-Aware Review)

不同于市面上常见的单文件对话，Agent-Review 支持递归扫描文件夹或多选文件。利用 ProjectRootManager 遍历项目目录树，构建跨文件代码图谱，能够识别接口调用不一致、跨文件冗余等复杂逻辑问题。

### 2. 精准定位与代码热注入 (Surgical Injection)

系统接收到 AI 修复建议后，会自动通过 WriteCommandAction 执行线程安全的 Document 修改，并联动 ScrollingModel 自动跳转至修改行，让开发者“瞬移”到修改现场。

### 3. 稳健的局部撤销系统 (Granular Undo)

技术核心：基于 IntelliJ 底层的 RangeMarker API 实现修改区域的动态追踪。

用户体验：即使修改后用户又在文件中插入了新代码，撤销标记也会随之自动偏移。用户可通过侧边栏卡片上的 Undo 按钮独立撤销单处修改，而不影响其他有效的代码修复。

### 4. 沉浸式交互 Chat 面板

基于 Swing 环境下的 JTextPane 与 HTMLEditorKit 渲染，提供了高仿 Amazon Q 风格的富文本交互界面。支持 HTML 卡片展示、文件链接跳转以及伪协议驱动的任务执行。

## 🏗️ 架构设计

项目采用前后端分离的 双端 Agent 架构：

Frontend (Java/Kotlin):

职责：代码上下文采集（PSI）、编辑器状态监听、UI 渲染、VFS 文件定位。

核心 API：AnAction, ToolWindow, RangeMarker, WriteCommandAction.

Backend (Python):

职责：LLM 接口封装、Prompt 模板工程、JSON 结构化输出校验与清理。

技术栈：FastAPI, OpenAI SDK, Pydantic.

## 🛠️ 技术栈

插件端: IntelliJ Platform SDK, Java 17, Gradle, Gson.

后端服务: Python 3.9+, FastAPI, Uvicorn, OpenAI SDK.

大模型: 通义千问 (Qwen-Plus/Max) 兼容接口。

## 🚀 快速开始

1. 启动后端服务

进入 agent，配置你的 API Key：

# 安装依赖
pip install fastapi uvicorn openai pydantic

# 启动服务 (默认端口 8000)
python agent.py


2. 运行插件

使用 IntelliJ IDEA 打开项目根目录。

找到 Gradle 工具栏，运行 intellij -> runIde。

在启动的沙箱 IDE 中，右键点击文件、选区或文件夹，选择 AI Code Review 启动审查。

📈 简历亮点建议

如果你打算将本项目写在简历中，可以强调以下难点攻克经验：

解决线程同步问题：如何利用 executeOnPooledThread 异步处理网络请求，并切回 invokeLater 安全更新 UI。

解决协议兼容性：在 Java HttpClient 中强制降级 HTTP/1.1 以确保与本地 Python 服务的 Body 传输稳定性。

解决 AI 幻觉匹配：通过字符串正则清理和换行符统一化逻辑，解决大模型输出 JSON 格式不规范导致的 indexOf 匹配失效。

📝 开源协议

MIT License