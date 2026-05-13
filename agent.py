import ast
import json
import os
import re
import subprocess
import tempfile
from enum import Enum
from typing import Any, Dict, List, Optional, TypedDict

from dotenv import load_dotenv
from fastapi import FastAPI
from langgraph.graph import END, StateGraph
from openai import OpenAI
from pydantic import BaseModel, Field

load_dotenv()

app = FastAPI(title="Agent-Review Multi-Agent Backend")

DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")

QWEN_BASE_URL = os.getenv("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")

CHEAP_MODEL = os.getenv("CHEAP_MODEL", "qwen3.5")
REVIEW_MODEL = os.getenv("REVIEW_MODEL", "qwen-plus")
CODER_MODEL = os.getenv("CODER_MODEL", "gpt-5.5")
MAX_REPAIR_ROUNDS = int(os.getenv("MAX_REPAIR_ROUNDS", "2"))

qwen_client = OpenAI(api_key=DASHSCOPE_API_KEY, base_url=QWEN_BASE_URL) if DASHSCOPE_API_KEY else None
openai_client = OpenAI(api_key=OPENAI_API_KEY, base_url=OPENAI_BASE_URL) if OPENAI_API_KEY else None


class TaskComplexity(str, Enum):
    SIMPLE = "simple"
    COMPLEX = "complex"


class FileInfo(BaseModel):
    path: str
    name: str
    content: str
    language: Optional[str] = None
    chunks: List[Dict[str, Any]] = Field(default_factory=list)


class ProjectRequest(BaseModel):
    files: List[FileInfo]
    task: Optional[str] = "code_review"
    standard_library_hits: List[Dict[str, Any]] = Field(default_factory=list)


class ReviewFinding(BaseModel):
    finding_id: str
    file_path: str
    target_snippet: str
    severity: str = "medium"
    dimensions: List[str] = Field(default_factory=list)
    diagnosis: str
    evidence: List[str] = Field(default_factory=list)
    standard_library_refs: List[str] = Field(default_factory=list)
    white_box_tests: List[Dict[str, Any]] = Field(default_factory=list)


class PatchItem(BaseModel):
    file_path: str
    target_snippet: str
    replacement_code: str
    explanation: str
    finding_id: Optional[str] = None


class QaResult(BaseModel):
    ok: bool
    errors: List[str] = Field(default_factory=list)


class SandboxTestResult(BaseModel):
    ok: bool
    applied_patches: int = 0
    executed_checks: List[str] = Field(default_factory=list)
    failures: List[str] = Field(default_factory=list)
    reviewer_test_plan: List[Dict[str, Any]] = Field(default_factory=list)


class AgentWorkflowState(TypedDict, total=False):
    request: ProjectRequest
    complexity: TaskComplexity
    findings: List[ReviewFinding]
    patches: List[PatchItem]
    sandbox: SandboxTestResult
    qa: QaResult
    qa_feedback: Optional[Dict[str, Any]]
    repair_round: int
    route: Dict[str, str]


class ModelRouter:
    """Routes inexpensive tasks to Qwen and code-changing tasks to stronger models."""

    def classify(self, request: ProjectRequest) -> TaskComplexity:
        total_chars = sum(len(file.content) for file in request.files)
        file_count = len(request.files)
        code_change_task = request.task in {"code_review", "repair", "patch"}
        if code_change_task or file_count > 1 or total_chars > 6000:
            return TaskComplexity.COMPLEX
        return TaskComplexity.SIMPLE

    def model_for(self, role: str, complexity: TaskComplexity) -> str:
        if role == "qa":
            return CHEAP_MODEL
        if complexity == TaskComplexity.SIMPLE:
            return CHEAP_MODEL
        if role == "reviewer":
            return REVIEW_MODEL
        return CODER_MODEL

    def client_for(self, model: str) -> OpenAI:
        model_name = model.lower()
        if model_name.startswith("qwen"):
            if not qwen_client:
                raise RuntimeError("DASHSCOPE_API_KEY is not configured for Qwen-compatible models.")
            return qwen_client
        if not openai_client:
            raise RuntimeError("OPENAI_API_KEY is not configured for non-Qwen models.")
        return openai_client


router = ModelRouter()


def dump_model(model: BaseModel) -> Dict[str, Any]:
    if hasattr(model, "model_dump"):
        return model.model_dump()
    return model.dict()


def detect_language(file: FileInfo) -> str:
    if file.language:
        return file.language.lower()
    name = file.name.lower()
    if name.endswith(".py"):
        return "python"
    if name.endswith(".java"):
        return "java"
    if name.endswith(".kt"):
        return "kotlin"
    if name.endswith(".js"):
        return "javascript"
    if name.endswith(".ts"):
        return "typescript"
    return "text"


def compact_context(request: ProjectRequest, max_chars_per_file: int = 14000) -> str:
    sections = []
    for file in request.files:
        chunks = file.chunks or [{
            "chunk_id": f"{file.path}:whole",
            "kind": "whole_file",
            "start_line": 1,
            "end_line": file.content.count("\n") + 1,
            "text": file.content,
        }]
        rendered_chunks = []
        used = 0
        for chunk in chunks:
            text = str(chunk.get("text") or "")
            if used + len(text) > max_chars_per_file:
                text = text[: max(0, max_chars_per_file - used)]
            if not text:
                continue
            used += len(text)
            rendered_chunks.append(
                "CHUNK {chunk_id} [{kind}] lines {start}-{end}\n{body}".format(
                    chunk_id=chunk.get("chunk_id", "unknown"),
                    kind=chunk.get("kind", "unknown"),
                    start=chunk.get("start_line", "?"),
                    end=chunk.get("end_line", "?"),
                    body=text,
                )
            )
            if used >= max_chars_per_file:
                break
        sections.append(f"FILE: {file.path}\nLANGUAGE: {detect_language(file)}\n" + "\n\n".join(rendered_chunks))
    return "\n\n---\n\n".join(sections)


def standard_library_context(request: ProjectRequest) -> str:
    if not request.standard_library_hits:
        return "No standard-library/RAG hits were supplied. Use only the submitted code and general secure coding rules."
    return json.dumps(request.standard_library_hits[:12], ensure_ascii=False, indent=2)


def call_llm(model: str, messages: List[Dict[str, str]], temperature: float = 0.1) -> str:
    client = router.client_for(model)
    completion = client.chat.completions.create(
        model=model,
        messages=messages,
        temperature=temperature,
    )
    return (completion.choices[0].message.content or "").strip()


def strip_json_fence(raw: str) -> str:
    text = raw.strip()
    if text.startswith("```json"):
        text = text[7:]
    elif text.startswith("```"):
        text = text[3:]
    if text.endswith("```"):
        text = text[:-3]
    return text.strip()


def parse_json_list(raw: str, field_name: str) -> List[Dict[str, Any]]:
    cleaned = strip_json_fence(raw)
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{field_name} is not valid JSON: {exc}\nRaw output:\n{raw}") from exc
    if not isinstance(data, list):
        raise ValueError(f"{field_name} must be a JSON array.")
    return data


def reviewer_agent(request: ProjectRequest, complexity: TaskComplexity) -> List[ReviewFinding]:
    model = router.model_for("reviewer", complexity)
    system_prompt = """You are Reviewer, a white-box review and test-design agent for AI Coding.
Compare the current code against the supplied standard-library/RAG evidence, identify precise defects, and design tests that can prove whether the patch is correct.
Do not only check whether code can run. Focus on behavior, edge cases, regressions, architecture specification compliance, cross-file interface consistency, and latent logic/security bugs.
Return ONLY a strict JSON array. Each item must contain:
finding_id, file_path, target_snippet, severity, dimensions, diagnosis, evidence, standard_library_refs, white_box_tests.
target_snippet must be an exact substring from the submitted file. Return [] if no actionable defect exists."""
    user_prompt = (
        "STANDARD_LIBRARY_RAG_HITS:\n"
        f"{standard_library_context(request)}\n\n"
        "PROJECT_CONTEXT:\n"
        f"{compact_context(request)}"
    )
    raw = call_llm(model, [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}])
    findings = parse_json_list(raw, "reviewer findings")
    return [ReviewFinding(**item) for item in findings]


def coder_agent(
    request: ProjectRequest,
    findings: List[ReviewFinding],
    complexity: TaskComplexity,
    qa_feedback: Optional[Dict[str, Any]] = None,
) -> List[PatchItem]:
    model = router.model_for("coder", complexity)
    system_prompt = """You are Coder, a patch-generation agent.
Generate minimal, directly injectable patches from Reviewer findings.
Return ONLY a strict JSON array. Each item must contain:
file_path, target_snippet, replacement_code, explanation, finding_id.
Do not invent file paths. target_snippet must remain exactly the original text to replace.
replacement_code must be complete code, never truncated, and must preserve surrounding indentation."""
    user_payload = {
        "findings": [dump_model(finding) for finding in findings],
        "project_context": compact_context(request),
        "qa_feedback_from_previous_round": qa_feedback,
    }
    raw = call_llm(
        model,
        [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
    )
    patches = parse_json_list(raw, "coder patches")
    return [PatchItem(**item) for item in patches]


def validate_balanced_code(code: str) -> List[str]:
    errors = []
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    in_string = None
    escaped = False
    for index, char in enumerate(code):
        if escaped:
            escaped = False
            continue
        if char == "\\":
            escaped = True
            continue
        if in_string:
            if char == in_string:
                in_string = None
            continue
        if char in {'"', "'"}:
            in_string = char
            continue
        if char in "([{":
            stack.append((char, index))
        elif char in pairs:
            if not stack or stack[-1][0] != pairs[char]:
                errors.append(f"Unmatched closing token '{char}' at offset {index}.")
            else:
                stack.pop()
    if in_string:
        errors.append(f"Unclosed string literal {in_string}.")
    for token, index in stack:
        errors.append(f"Unclosed token '{token}' at offset {index}.")
    return errors


def validate_python(code: str) -> List[str]:
    try:
        ast.parse(code)
        return []
    except SyntaxError as exc:
        return [f"Python syntax error at line {exc.lineno}: {exc.msg}"]


def validate_java_with_javac(code: str) -> List[str]:
    if "class " not in code:
        return []
    match = re.search(r"\b(public\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)", code)
    if not match:
        return []
    class_name = match.group(2)
    source = code if match.group(1) else re.sub(r"\bclass\s+" + re.escape(class_name), "public class " + class_name, code, count=1)
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, f"{class_name}.java")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(source)
        try:
            result = subprocess.run(["javac", path], capture_output=True, text=True, timeout=10)
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return []
        if result.returncode != 0:
            return [line for line in result.stderr.splitlines() if line.strip()][:12]
    return []


def collect_reviewer_test_plan(findings: List[ReviewFinding]) -> List[Dict[str, Any]]:
    tests = []
    for finding in findings:
        for index, test in enumerate(finding.white_box_tests):
            normalized = dict(test)
            normalized.setdefault("finding_id", finding.finding_id)
            normalized.setdefault("test_id", f"{finding.finding_id}-T{index + 1}")
            tests.append(normalized)
    return tests


def apply_patches_to_sandbox(request: ProjectRequest, patches: List[PatchItem], sandbox_dir: str) -> Dict[str, str]:
    content_by_path = {file.path: file.content for file in request.files}
    for patch in patches:
        original = content_by_path.get(patch.file_path)
        if original is None:
            raise ValueError(f"Patch references unknown file: {patch.file_path}")
        if patch.target_snippet not in original:
            raise ValueError(f"target_snippet not found in {patch.file_path}")
        content_by_path[patch.file_path] = original.replace(patch.target_snippet, patch.replacement_code, 1)

    sandbox_paths = {}
    for file in request.files:
        relative_name = os.path.basename(file.path) or file.name
        target_path = os.path.join(sandbox_dir, relative_name)
        with open(target_path, "w", encoding="utf-8") as handle:
            handle.write(content_by_path[file.path])
        sandbox_paths[file.path] = target_path
    return sandbox_paths


def run_sandbox_tests(
    request: ProjectRequest,
    findings: List[ReviewFinding],
    patches: List[PatchItem],
) -> SandboxTestResult:
    failures = []
    executed_checks = []
    test_plan = collect_reviewer_test_plan(findings)

    with tempfile.TemporaryDirectory(prefix="agent_review_sandbox_") as sandbox_dir:
        try:
            sandbox_paths = apply_patches_to_sandbox(request, patches, sandbox_dir)
            executed_checks.append("apply_patches_to_temp_sandbox")
        except Exception as exc:
            return SandboxTestResult(
                ok=False,
                applied_patches=0,
                executed_checks=executed_checks,
                failures=[str(exc)],
                reviewer_test_plan=test_plan,
            )

        for file in request.files:
            language = detect_language(file)
            sandbox_path = sandbox_paths[file.path]
            if language == "python":
                executed_checks.append(f"python_ast:{file.name}")
                with open(sandbox_path, "r", encoding="utf-8") as handle:
                    failures.extend(validate_python(handle.read()))
                executed_checks.append(f"python_py_compile:{file.name}")
                result = subprocess.run(
                    ["python", "-m", "py_compile", sandbox_path],
                    capture_output=True,
                    text=True,
                    timeout=10,
                    cwd=sandbox_dir,
                )
                if result.returncode != 0:
                    failures.extend([line for line in result.stderr.splitlines() if line.strip()][:8])
            elif language == "java":
                executed_checks.append(f"javac_if_available:{file.name}")
                with open(sandbox_path, "r", encoding="utf-8") as handle:
                    failures.extend(validate_java_with_javac(handle.read()))

    if test_plan:
        executed_checks.append("reviewer_white_box_test_plan_generated")
    return SandboxTestResult(
        ok=not failures,
        applied_patches=len(patches),
        executed_checks=executed_checks,
        failures=failures,
        reviewer_test_plan=test_plan,
    )


def qa_agent(
    request: ProjectRequest,
    patches: List[PatchItem],
    sandbox_result: Optional[SandboxTestResult] = None,
) -> QaResult:
    errors = []
    language_by_path = {file.path: detect_language(file) for file in request.files}
    for patch in patches:
        code = patch.replacement_code
        patch_errors = validate_balanced_code(code)
        language = language_by_path.get(patch.file_path, "text")
        if language == "python":
            patch_errors.extend(validate_python(code))
        elif language == "java":
            patch_errors.extend(validate_java_with_javac(code))
        if patch_errors:
            errors.append(
                json.dumps(
                    {
                        "finding_id": patch.finding_id,
                        "file_path": patch.file_path,
                        "errors": patch_errors,
                        "patch": dump_model(patch),
                    },
                    ensure_ascii=False,
                )
            )
    if sandbox_result and not sandbox_result.ok:
        errors.append(
            json.dumps(
                {
                    "stage": "sandbox_white_box_tests",
                    "failures": sandbox_result.failures,
                    "executed_checks": sandbox_result.executed_checks,
                    "reviewer_test_plan": sandbox_result.reviewer_test_plan,
                },
                ensure_ascii=False,
            )
        )
    return QaResult(ok=not errors, errors=errors)


def route_node(state: AgentWorkflowState) -> AgentWorkflowState:
    request = state["request"]
    complexity = router.classify(request)
    return {
        **state,
        "complexity": complexity,
        "repair_round": 0,
        "route": {
            "reviewer": router.model_for("reviewer", complexity),
            "coder": router.model_for("coder", complexity),
            "qa": router.model_for("qa", complexity),
        },
    }


def reviewer_node(state: AgentWorkflowState) -> AgentWorkflowState:
    findings = reviewer_agent(state["request"], state["complexity"])
    return {**state, "findings": findings}


def coder_node(state: AgentWorkflowState) -> AgentWorkflowState:
    patches = coder_agent(
        state["request"],
        state.get("findings", []),
        state["complexity"],
        qa_feedback=state.get("qa_feedback"),
    )
    return {**state, "patches": patches, "qa_feedback": None}


def sandbox_test_node(state: AgentWorkflowState) -> AgentWorkflowState:
    sandbox = run_sandbox_tests(
        state["request"],
        state.get("findings", []),
        state.get("patches", []),
    )
    return {**state, "sandbox": sandbox}


def qa_node(state: AgentWorkflowState) -> AgentWorkflowState:
    qa = qa_agent(state["request"], state.get("patches", []), state.get("sandbox"))
    next_state: AgentWorkflowState = {**state, "qa": qa}
    if not qa.ok:
        repair_round = state.get("repair_round", 0) + 1
        next_state["repair_round"] = repair_round
        next_state["qa_feedback"] = {
            "round": repair_round,
            "errors": qa.errors,
            "original_patches": [dump_model(patch) for patch in state.get("patches", [])],
        }
    return next_state


def after_reviewer(state: AgentWorkflowState) -> str:
    if not state.get("findings"):
        return "finish"
    return "coder"


def after_qa(state: AgentWorkflowState) -> str:
    qa = state.get("qa")
    if qa and qa.ok:
        return "finish"
    if state.get("repair_round", 0) < MAX_REPAIR_ROUNDS:
        return "coder"
    return "finish"


def build_review_graph():
    workflow = StateGraph(AgentWorkflowState)
    workflow.add_node("route", route_node)
    workflow.add_node("reviewer", reviewer_node)
    workflow.add_node("coder", coder_node)
    workflow.add_node("sandbox_test", sandbox_test_node)
    workflow.add_node("qa", qa_node)

    workflow.set_entry_point("route")
    workflow.add_edge("route", "reviewer")
    workflow.add_conditional_edges("reviewer", after_reviewer, {"coder": "coder", "finish": END})
    workflow.add_edge("coder", "sandbox_test")
    workflow.add_edge("sandbox_test", "qa")
    workflow.add_conditional_edges("qa", after_qa, {"coder": "coder", "finish": END})
    return workflow.compile()


review_graph = build_review_graph()


@app.post("/api/project_review")
async def project_review(request: ProjectRequest):
    if not DASHSCOPE_API_KEY and not OPENAI_API_KEY:
        return {"status": "error", "message": "No model API key configured. Set DASHSCOPE_API_KEY and/or OPENAI_API_KEY."}

    try:
        state = review_graph.invoke({"request": request})
        complexity = state["complexity"]
        findings = state.get("findings", [])
        if not findings:
            return {
                "status": "success",
                "complexity": complexity,
                "route": state.get("route", {"reviewer": router.model_for("reviewer", complexity)}),
                "findings": [],
                "reviews": [],
                "qa": {"ok": True, "errors": []},
                "workflow": "langgraph",
            }

        patches = state.get("patches", [])
        qa = state.get("qa", QaResult(ok=True))

        return {
            "status": "success" if qa.ok else "qa_failed",
            "complexity": complexity,
            "route": state.get("route", {}),
            "findings": [dump_model(finding) for finding in findings],
            "reviews": [dump_model(patch) for patch in patches],
            "sandbox": dump_model(state.get("sandbox", SandboxTestResult(ok=True))),
            "qa": dump_model(qa),
            "repair_rounds": state.get("repair_round", 0),
            "workflow": "langgraph",
        }
    except Exception as exc:
        return {"status": "error", "message": str(exc)}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
