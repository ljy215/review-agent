package com.work.agentreview;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 升级版 AgentReviewAction：支持全项目/多文件上下文感知与跨文件精准修复
 */
public class AgentReviewAction extends AnAction {
    private static final int MAX_CHUNK_TOKENS = 1800;

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // 1. 获取选中的上下文 (可能是编辑器选区，也可能是项目树中选中的多个文件/文件夹)
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        List<Map<String, Object>> filesToScan = new ArrayList<>();

        if (editor != null && editor.getSelectionModel().hasSelection()) {
            // 情况 A: 用户在编辑器里选中了一段特定的代码
            Document doc = editor.getDocument();
            VirtualFile vFile = FileDocumentManager.getInstance().getFile(doc);
            if (vFile != null) {
                Map<String, Object> fileData = new HashMap<>();
                fileData.put("path", vFile.getPath());
                fileData.put("name", vFile.getName());
                String selectedText = editor.getSelectionModel().getSelectedText();
                fileData.put("content", selectedText);
                fileData.put("language", detectLanguage(vFile));
                fileData.put("chunks", buildSelectionChunk(vFile, editor, selectedText));
                filesToScan.add(fileData);
            }
        } else if (selectedFiles != null) {
            // 情况 B: 用户在项目树右键点击了文件或文件夹
            for (VirtualFile file : selectedFiles) {
                collectFiles(project, file, filesToScan);
            }
        }

        if (filesToScan.isEmpty()) return;

        // 2. 侧边栏交互：显示扫描进度
        showInChat(project, "🧑‍💻 You", "正在请求分析 " + filesToScan.size() + " 个文件的上下文...");

        // 3. 发起全项目级别异步请求
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
                JsonObject requestBody = new JsonObject();
                requestBody.add("files", gson.toJsonTree(filesToScan));
                String jsonBody = gson.toJson(requestBody);

                HttpClient client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofMinutes(1)) // 增加超时时间以应对大项目
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:8000/api/project_review"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    handleProjectResponse(project, response.body());
                } else {
                    showInChat(project, "❌ System", "Agent 拒绝请求 (422/500)。请检查后端日志。");
                }
            } catch (Exception ex) {
                showInChat(project, "❌ System", "分析失败: " + ex.getMessage());
            }
        });
    }

    /**
     * 递归收集文件夹下的所有代码文件
     */
    private void collectFiles(Project project, VirtualFile file, List<Map<String, Object>> fileList) {
        if (file.isDirectory()) {
            for (VirtualFile child : file.getChildren()) {
                collectFiles(project, child, fileList);
            }
        } else {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".ts")) {
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                if (doc != null) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("path", file.getPath());
                    data.put("name", file.getName());
                    String content = doc.getText();
                    data.put("content", content);
                    data.put("language", detectLanguage(file));
                    data.put("chunks", buildSemanticChunks(project, file, doc));
                    fileList.add(data);
                }
            }
        }
    }

    private String detectLanguage(VirtualFile file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".kt")) return "kotlin";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".js")) return "javascript";
        if (name.endsWith(".ts")) return "typescript";
        return "text";
    }

    private List<Map<String, Object>> buildSelectionChunk(VirtualFile file, Editor editor, String selectedText) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        int startOffset = editor.getSelectionModel().getSelectionStart();
        int endOffset = editor.getSelectionModel().getSelectionEnd();
        chunks.add(createChunk(
                file.getPath() + ":selection:" + startOffset + "-" + endOffset,
                "selection",
                editor.getDocument().getLineNumber(startOffset) + 1,
                editor.getDocument().getLineNumber(Math.max(startOffset, endOffset - 1)) + 1,
                selectedText
        ));
        return chunks;
    }

    private List<Map<String, Object>> buildSemanticChunks(Project project, VirtualFile file, Document doc) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc);

        if (psiFile != null && "java".equals(detectLanguage(file))) {
            for (PsiClass psiClass : PsiTreeUtil.findChildrenOfType(psiFile, PsiClass.class)) {
                addPsiChunk(file, doc, chunks, psiClass, "class");
            }
            for (PsiMethod psiMethod : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
                addPsiChunk(file, doc, chunks, psiMethod, "method");
            }
        }

        if (chunks.isEmpty()) {
            chunks.addAll(splitByTokenBudget(file.getPath(), "text_window", doc.getText(), 1));
        }
        return chunks;
    }

    private void addPsiChunk(VirtualFile file, Document doc, List<Map<String, Object>> chunks, PsiElement element, String kind) {
        int start = element.getTextRange().getStartOffset();
        int end = element.getTextRange().getEndOffset();
        String text = doc.getText().substring(start, end);
        int estimatedTokens = estimateTokens(text);
        if (estimatedTokens <= MAX_CHUNK_TOKENS) {
            chunks.add(createChunk(
                    file.getPath() + ":" + kind + ":" + start + "-" + end,
                    kind,
                    doc.getLineNumber(start) + 1,
                    doc.getLineNumber(Math.max(start, end - 1)) + 1,
                    text
            ));
        } else {
            chunks.addAll(splitByTokenBudget(file.getPath() + ":" + kind, kind + "_window", text, doc.getLineNumber(start) + 1));
        }
    }

    private List<Map<String, Object>> splitByTokenBudget(String chunkPrefix, String kind, String text, int baseLine) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder();
        int startLine = baseLine;
        int line = baseLine;
        int index = 0;
        for (String nextLine : lines) {
            String candidate = current + nextLine + "\n";
            if (estimateTokens(candidate) > MAX_CHUNK_TOKENS && current.length() > 0) {
                chunks.add(createChunk(chunkPrefix + ":" + index, kind, startLine, line - 1, current.toString()));
                current.setLength(0);
                startLine = line;
                index++;
            }
            current.append(nextLine).append("\n");
            line++;
        }
        if (current.length() > 0) {
            chunks.add(createChunk(chunkPrefix + ":" + index, kind, startLine, line - 1, current.toString()));
        }
        return chunks;
    }

    private Map<String, Object> createChunk(String id, String kind, int startLine, int endLine, String text) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("chunk_id", id);
        chunk.put("kind", kind);
        chunk.put("start_line", startLine);
        chunk.put("end_line", endLine);
        chunk.put("estimated_tokens", estimateTokens(text));
        chunk.put("text", text);
        return chunk;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int asciiTokenEstimate = Math.max(1, text.length() / 4);
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) nonAscii++;
        }
        return asciiTokenEstimate + nonAscii;
    }

    /**
     * 处理跨文件修改建议
     */
    private void handleProjectResponse(Project project, String responseBody) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Gson gson = new Gson();
            JsonObject responseObj = gson.fromJson(responseBody, JsonObject.class);
            if (!responseObj.has("reviews")) return;

            JsonArray reviews = responseObj.getAsJsonArray("reviews");
            Map<String, Runnable> undoMap = new HashMap<>();
            StringBuilder chatHtml = new StringBuilder("<b>审查完成！</b><br>");

            for (JsonElement element : reviews) {
                JsonObject item = element.getAsJsonObject();
                String filePath = item.get("file_path").getAsString();
                String target = item.get("target_snippet").getAsString().replace("\r\n", "\n");
                String replacement = item.get("replacement_code").getAsString();
                String explanation = item.get("explanation").getAsString();

                // 🌟 通过路径寻找 VirtualFile
                VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath);
                if (vFile != null) {
                    Document doc = FileDocumentManager.getInstance().getDocument(vFile);
                    if (doc != null) {
                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            String text = doc.getText();
                            int index = text.indexOf(target);
                            if (index != -1) {
                                final String original = text.substring(index, index + target.length());
                                doc.replaceString(index, index + target.length(), replacement);

                                // 创建跨文件追踪器
                                RangeMarker marker = doc.createRangeMarker(index, index + replacement.length());
                                String actionId = UUID.randomUUID().toString();

                                // 注册撤销动作
                                undoMap.put(actionId, () -> ApplicationManager.getApplication().invokeLater(() ->
                                        WriteCommandAction.runWriteCommandAction(project, () -> {
                                            if (marker.isValid()) {
                                                doc.replaceString(marker.getStartOffset(), marker.getEndOffset(), original);
                                                marker.dispose();
                                            }
                                        })
                                ));

                                // 渲染精美卡片
                                int line = doc.getLineNumber(index) + 1;
                                chatHtml.append("<div style='background:#2B2D30; padding:8px; border-radius:6px; margin-top:5px; border:1px solid #43454A;'>")
                                        .append("<table width='100%'><tr>")
                                        .append("<td><b style='color:#A9B7C6;'>📄 ").append(vFile.getName()).append("</b>")
                                        .append("<span style='color:#6A8759;'> (第 ").append(line).append(" 行)</span></td>")
                                        .append("<td align='right'><a href='undo://").append(actionId).append("' style='color:#589DF6;'>Undo</a></td>")
                                        .append("</tr></table>")
                                        .append("<div style='color:#808080; font-size:11px; margin-top:4px;'>💡 ").append(explanation).append("</div>")
                                        .append("</div>");

                                // 🌟 修复：使用 EditorFactory 获取 Document 关联的所有文本编辑器
                                Editor[] editors = EditorFactory.getInstance().getEditors(doc, project);
                                if (editors.length > 0) {
                                    editors[0].getScrollingModel().scrollTo(editors[0].offsetToLogicalPosition(index), ScrollType.CENTER);
                                }
                            }
                        });
                    }
                }
            }

            AgentChatPanel panel = project.getUserData(AgentChatPanel.PANEL_KEY);
            if (panel != null) {
                panel.registerUndoActions(undoMap);
                panel.appendRawHTML("🤖 Agent", chatHtml.toString(), "#009688");
            }
        });
    }

    private void showInChat(Project project, String sender, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Agent Chat");
            if (tw != null) tw.show();
            AgentChatPanel panel = project.getUserData(AgentChatPanel.PANEL_KEY);
            if (panel != null) panel.appendMessage(sender, message);
        });
    }
}
