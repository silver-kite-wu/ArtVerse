package com.artverse.application;

import org.springframework.stereotype.Component;

@Component
public class StoryChatRouter {

    public StoryChatRoute classify(String message) {
        String text = message == null ? "" : message.trim().toLowerCase();
        if (text.isBlank()) {
            return StoryChatRoute.READ;
        }
        boolean save = containsAny(text, "保存", "写入", "提交", "更新正文", "替换正文",
                "存到小说原文", "保存到小说原文", "写进小说原文", "save", "commit");
        boolean draft = containsAny(text, "草稿", "续写", "扩写", "生成正文", "写正文", "正文候选", "draft");
        boolean polish = containsAny(text, "润色", "改写", "优化", "修改", "修订", "精修", "polish", "rewrite");
        boolean review = containsAny(text, "审核", "审阅", "审查", "点评", "评价", "评估", "检查", "问题", "review", "feedback");
        if (save && (draft || polish || containsAny(text, "原文", "正文", "章节"))) {
            return StoryChatRoute.WRITE;
        }
        if (save) {
            return StoryChatRoute.WRITE;
        }
        if (polish) {
            return StoryChatRoute.POLISH;
        }
        if (review) {
            return StoryChatRoute.REVIEW;
        }
        if (draft) {
            return StoryChatRoute.DRAFT;
        }
        return StoryChatRoute.READ;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
