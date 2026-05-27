# Agent-Review

Agent-Review 是一个集成在 IntelliJ IDEA 中的多智能体代码审查与自动修复系统。插件端基于 IntelliJ PSI 采集代码上下文并进行语义级切块，后端基于 FastAPI 和 LangGraph 编排 Reviewer、Coder、Sandbox Tester、QA Agent，形成“诊断 - 修复 - 沙箱验证 - 质量校验 - 失败反馈”的闭环。

## 核心能力

### 1. IDEA 语义级代码采集

插件端通过 IntelliJ Platform SDK 获取用户选中的文件、目录或代码片段，并结合 PSI 对 Java 文件进行类、方法级 Chunk 切分。每个 Chunk 会携带文件路径、语言、起止行号和 token 估算，尽量在控制上下文长度的同时保留代码结构完整性。

### 2. LangGraph 多智能体工作流

后端使用 LangGraph `StateGraph` 组织多 Agent 协作：

```text
route -> reviewer -> coder -> sandbox_test -> qa
```

- `Reviewer`：根据代码上下文和标准库 RAG 召回内容定位缺陷，并设计白盒测试用例。
- `Coder`：根据 Reviewer 的结构化诊断报告生成最小可注入补丁。
- `Sandbox Tester`：在临时沙箱目录中应用补丁并运行受控检查，避免污染真实工作区。
- `QA Agent`：结合补丁静态校验、沙箱结果和白盒测试计划判断补丁质量。

### 3. 结构化补丁注入

模型输出不直接修改整个文件，而是返回结构化 JSON：

```json
{
  "file_path": "文件路径",
  "target_snippet": "原始代码片段",
  "replacement_code": "修复后的代码",
  "explanation": "修复原因"
}
```

插件端通过 `target_snippet` 精确定位原代码，再使用 `WriteCommandAction` 执行安全替换。

### 4. 沙箱测试与自我纠错

Coder 生成补丁后，系统先在临时目录中应用补丁并运行受控检查，例如 Python AST、`python -m py_compile`、Java `javac`。如果 QA 判定失败，系统会把错误堆栈、测试失败信息和原补丁回传给 Coder，在最大修复轮次内进行迭代。

### 5. 局部撤销

插件端使用 IntelliJ `RangeMarker` 跟踪每个补丁修改区域。用户可以在侧边栏结果卡片中单独撤销某个补丁，而不影响其他修改。

## 技术栈

插件端：

- IntelliJ Platform SDK
- Java 21
- Gradle
- Gson
- Swing / ToolWindow / PSI / VFS / RangeMarker

后端：

- Python 3.9+
- FastAPI
- LangGraph
- Pydantic
- OpenAI SDK compatible API
- DashScope / Qwen compatible mode

## 快速开始

### 1. 启动后端

在项目根目录安装依赖：

```bash
pip install -r requirements.txt
```

配置环境变量：

```bash
DASHSCOPE_API_KEY=你的 DashScope Key
OPENAI_API_KEY=你的 OpenAI Key
CHEAP_MODEL=qwen3.5
REVIEW_MODEL=qwen-plus
CODER_MODEL=gpt-5.5
MAX_REPAIR_ROUNDS=2
```

启动服务：

```bash
python agent.py
```

默认服务地址：

```text
http://127.0.0.1:8000/api/project_review
```

### 2. 运行 IDEA 插件

使用 IntelliJ IDEA 打开 `Agent-review/` 插件工程，运行 Gradle 的 `runIde`。在启动的沙箱 IDE 中，右键点击文件、选区或目录，选择 `AI Code Review` 启动审查。

## 关键文件

```text
agent.py
  后端 FastAPI 入口、LangGraph 工作流、Agent 定义、沙箱测试和 QA 逻辑

Agent-review/src/main/java/com/work/agentreview/AgentReviewAction.java
  IDEA 右键 Action、PSI 语义切块、调用后端、应用补丁

Agent-review/src/main/java/com/work/agentreview/AgentChatPanel.java
  侧边栏结果展示和局部 Undo 处理

Agent-review/src/main/resources/META-INF/plugin.xml
  插件 Action 与 ToolWindow 注册

项目设计维护文档.md
  后续维护用的目录结构和模块边界说明
```

## 文档

- `MULTI_AGENT_ARCHITECTURE.md`：多智能体架构说明
- `多智能体代码审查系统说明文档.md`：系统设计说明
- `多轮修复失败优化方案.md`：多轮失败、上下文退化和人工介入策略
- `项目设计维护文档.md`：维护视角的目录结构和常改文件

## 维护原则

- 后端接口优先保持 `/api/project_review` 兼容。
- 自动补丁先进入沙箱测试，最终通过 QA 后再注入真实文件。
- 多轮失败时优先做失败分析和上下文压缩，不盲目堆历史重试。
- 插件端修改真实代码必须通过 `WriteCommandAction`。
- 每个自动补丁都应保留局部撤销能力。

## License

MIT License
