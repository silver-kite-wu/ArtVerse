package com.artverse.agent;

import java.util.Locale;

/**
 * AI 运行时的工具权限。只描述产品审核边界，不启用框架级权限弹窗。
 */
public enum AiPermissionMode {
    MANUAL("手动审批", "只能读取资料并给出建议，不直接创建草稿或提案；所有修改由你确认后执行。"),
    AUTO("自动审批", "可自动创建草稿和提案；正式内容提交仍需你确认。"),
    FULL("完全访问", "可自动执行全部已授权工具，包括最终提交。");

    private final String label;
    private final String description;

    AiPermissionMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 空值或未知值返回 null，由调用方决定默认值。 */
    public static AiPermissionMode parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 交互式助手只保留只读工具与提问工具；自动审批保留草稿/提案；完全访问不裁剪。 */
    public boolean allows(String toolName, boolean readOnly) {
        if (readOnly || "ask_user".equals(toolName)) return true;
        return switch (this) {
            case MANUAL -> false;
            case AUTO -> !toolName.startsWith("commit_");
            case FULL -> true;
        };
    }

    public String promptNote() {
        return "\n\n[运行权限] " + label + "：" + description;
    }
}
