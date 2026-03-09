package com.work.agentreview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class AgentChatToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 实例化我们刚刚写的面板
        AgentChatPanel chatPanel = new AgentChatPanel(project);
        // 🌟 新增：把刚才创建的侧边栏面板，用钥匙存到当前 Project 的全局上下文里！
        project.putUserData(AgentChatPanel.PANEL_KEY, chatPanel);
        // 将面板注册为 ToolWindow 的内容
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(chatPanel.getContent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}