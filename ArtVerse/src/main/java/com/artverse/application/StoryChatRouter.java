package com.artverse.application;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StoryChatRouter {

    /** “给出修改意见/修改建议”是在索取评审结论，不是要求直接改写正文。 */
    private static final List<String> REVIEW_ADVICE_PHRASES = List.of(
            "修改意见", "修改建议", "修改方案", "修改思路", "修改点", "修改之处", "修改办法",
            "改进意见", "改进建议");

    public StoryChatRoute classify(String message) {
        String text = message == null ? "" : message.trim().toLowerCase();
        if (text.isBlank()) {
            return StoryChatRoute.READ;
        }
        String editText = text;
        for (String phrase : REVIEW_ADVICE_PHRASES) {
            editText = editText.replace(phrase, "");
        }
        boolean save = containsAny(text, "保存", "写入", "提交", "更新正文", "替换正文",
                "存到小说原文", "保存到小说原文", "写进小说原文", "save", "commit");
        boolean draft = containsAny(text, "草稿", "续写", "扩写", "生成正文", "写正文", "正文候选", "draft");
        boolean polish = containsAny(editText, "润色", "改写", "优化", "修改", "修订", "精修", "polish", "rewrite");
        boolean explicitEdit = containsAny(editText, "润色", "改写", "修改", "修订", "精修", "重写", "优化一下",
                "进行优化", "帮我优化", "请优化", "polish", "rewrite", "optimize");
        boolean review = containsAny(text, "评审", "审核", "审阅", "审查", "点评", "评价", "评估", "检查", "问题", "review", "feedback")
                || !editText.equals(text);
        boolean restore = containsAny(text, "恢复", "还原", "回滚", "restore", "revert", "rollback")
                && containsAny(text, "正文", "历史", "版本", "旧稿", "revision", "version", "prose", "draft")
                && !containsAny(text, "不要", "不需要", "如何", "怎么", "能否", "是否", "do not", "don't", "how to", "can i");
        if (restore) return StoryChatRoute.WRITE;
        if (save && (draft || polish || containsAny(text, "原文", "正文", "章节"))) {
            return StoryChatRoute.WRITE;
        }
        if (save) {
            return StoryChatRoute.WRITE;
        }
        if (review && !explicitEdit) {
            return StoryChatRoute.REVIEW;
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
