import json
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
from openai import OpenAI

app = FastAPI()

# 初始化 OpenAI 客户端 (以阿里云百炼为例)
# 建议在实际使用时将 API Key 放入环境变量
client = OpenAI(
    api_key="sk-0094bd01a705420d8ddb16d6ba04647c",
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1"
)


class FileInfo(BaseModel):
    path: str
    name: str
    content: str


class ProjectRequest(BaseModel):
    files: List[FileInfo]


@app.post("/api/project_review")
async def project_review(request: ProjectRequest):
    """
    全项目审查接口：接收多个文件上下文，识别跨文件的逻辑问题并给出修复建议。
    """
    # 1. 构建项目上下文描述
    context_desc = "\n".join([f"文件路径: {f.path}\n内容:\n{f.content}\n---" for f in request.files])

    system_prompt = """你是一个高级系统架构师。
任务：分析给出的多个项目文件，识别跨文件的逻辑漏洞、冗余、安全隐患或不一致之处。
要求：
1. 必须返回严格的 JSON 数组格式，不要包含任何 Markdown 标记（如 ```json）。
2. 每个对象必须包含以下字段：
   - file_path: 必须是输入中提供的完整绝对路径。
   - target_snippet: 该文件中要被替换的精确原代码文本。
   - replacement_code: 修复后的纯净代码。
   - explanation: 简洁的修改原因说明。
3. 如果代码没有问题，请返回空数组 []。"""

    user_prompt = f"以下是项目的源代码上下文：\n{context_desc}\n\n请进行深度审查并直接给出修复方案的 JSON 数组。"

    try:
        # 2. 呼叫大模型 (建议使用 qwen-plus 或 qwen-max 以处理大量代码上下文)
        completion = client.chat.completions.create(
            model="qwen-plus",
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            temperature=0.1  # 降低随机性，确保 JSON 格式稳定
        )

        raw_content = completion.choices[0].message.content.strip()

        # 3. 暴力清理大模型可能带出的 Markdown 代码块标签
        if raw_content.startswith("```json"):
            raw_content = raw_content[7:-3].strip()
        elif raw_content.startswith("```"):
            raw_content = raw_content[3:-3].strip()

        # 4. 解析并返回结果
        try:
            reviews = json.loads(raw_content)
            print(f"✅ 审查完成，发现 {len(reviews)} 处可优化项。")
            return {"status": "success", "reviews": reviews}
        except json.JSONDecodeError as je:
            print(f"❌ JSON 解析失败: {je}\n原始输出: {raw_content}")
            return {"status": "error", "message": "大模型返回格式非标准 JSON", "raw": raw_content}

    except Exception as e:
        print(f"❌ API 调用发生异常: {str(e)}")
        return {"status": "error", "message": str(e)}


if __name__ == "__main__":
    import uvicorn

    # 启动服务
    uvicorn.run(app, host="127.0.0.1", port=8000)