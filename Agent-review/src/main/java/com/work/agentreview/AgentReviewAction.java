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

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // 1. 获取选中的上下文 (可能是编辑器选区，也可能是项目树中选中的多个文件/文件夹)
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        List<Map<String, String>> filesToScan = new ArrayList<>();

        if (editor != null && editor.getSelectionModel().hasSelection()) {
            // 情况 A: 用户在编辑器里选中了一段特定的代码
            Document doc = editor.getDocument();
            VirtualFile vFile = FileDocumentManager.getInstance().getFile(doc);
            if (vFile != null) {
                Map<String, String> fileData = new HashMap<>();
                fileData.put("path", vFile.getPath());
                fileData.put("name", vFile.getName());
                fileData.put("content", editor.getSelectionModel().getSelectedText());
                filesToScan.add(fileData);
            }
        } else if (selectedFiles != null) {
            // 情况 B: 用户在项目树右键点击了文件或文件夹
            for (VirtualFile file : selectedFiles) {
                collectFiles(file, filesToScan);
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
    private void collectFiles(VirtualFile file, List<Map<String, String>> fileList) {
        if (file.isDirectory()) {
            for (VirtualFile child : file.getChildren()) {
                collectFiles(child, fileList);
            }
        } else {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".ts")) {
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                if (doc != null) {
                    Map<String, String> data = new HashMap<>();
                    data.put("path", file.getPath());
                    data.put("name", file.getName());
                    data.put("content", doc.getText());
                    fileList.add(data);
                }
            }
        }
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