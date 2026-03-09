package com.work.agentreview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentChatPanel {
    public static final Key<AgentChatPanel> PANEL_KEY = Key.create("AgentChatPanel");

    private final JPanel mainPanel;
    private final JTextPane chatHistoryPane;
    private final HTMLEditorKit htmlKit;
    private final HTMLDocument htmlDoc;

    private final JBTextArea inputArea;
    private final JButton sendButton;

    private final Project project;

    // 🌟 核心：用一个 Map 存储当前所有的“局部撤销”动作
    private final Map<String, Runnable> undoActions = new ConcurrentHashMap<>();

    public AgentChatPanel(Project project) {
        this.project = project;
        mainPanel = new JPanel(new BorderLayout());

        chatHistoryPane = new JTextPane();
        chatHistoryPane.setEditable(false);
        chatHistoryPane.setBackground(UIManager.getColor("EditorPane.background"));

        htmlKit = new HTMLEditorKit();
        chatHistoryPane.setEditorKit(htmlKit);
        htmlDoc = (HTMLDocument) htmlKit.createDefaultDocument();
        chatHistoryPane.setDocument(htmlDoc);

        // 🌟 监听卡片上的 Undo 按钮点击事件
        chatHistoryPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                String desc = e.getDescription();
                if (desc != null && desc.startsWith("undo://")) {
                    String id = desc.substring(7); // 提取动作 ID
                    Runnable action = undoActions.remove(id); // 取出并移除（防止重复点击）
                    if (action != null) {
                        action.run();
                        appendRawHTML("⚙️ System", "✅ 已成功撤销该代码块的修改。", "#E53935");
                    } else {
                        appendRawHTML("⚠️ System", "该撤销动作已失效。", "#808080");
                    }
                }
            }
        });

        JBScrollPane historyScrollPane = new JBScrollPane(chatHistoryPane);
        historyScrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputArea = new JBTextArea(4, 20);
        inputArea.setLineWrap(true);
        JBScrollPane inputScrollPane = new JBScrollPane(inputArea);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 1, 0, 5));
        sendButton = new JButton("发送 (Send)");
        buttonPanel.add(sendButton);

        bottomPanel.add(inputScrollPane, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        mainPanel.add(historyScrollPane, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        appendRawHTML("🤖 Agent", "你好！我是你的 AI 编程助手。", "#009688");
    }

    public JPanel getContent() { return mainPanel; }

    public void appendMessage(String sender, String message) {
        String formattedMsg = message.replace("\n", "<br>");
        String color = sender.contains("You") ? "#4C70F0" : "#009688";
        appendRawHTML(sender, formattedMsg, color);
    }

    // 🌟 专门用来渲染复杂 HTML 的方法
    public void appendRawHTML(String sender, String htmlContent, String senderColor) {
        String htmlText = "<div style='margin-bottom: 15px; font-family: sans-serif; font-size: 13px;'>" +
                "<b style='color:" + senderColor + ";'>" + sender + "</b><br>" +
                "<div style='margin-top: 5px; color: #CCCCCC;'>" + htmlContent + "</div></div>";
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                htmlKit.insertHTML(htmlDoc, htmlDoc.getLength(), htmlText, 0, 0, null);
                chatHistoryPane.setCaretPosition(htmlDoc.getLength());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // 🌟 供外部注册撤销动作
    public void registerUndoActions(Map<String, Runnable> actions) {
        if (actions != null) {
            undoActions.putAll(actions);
        }
    }
}