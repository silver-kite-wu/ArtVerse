package com.artverse.application.workflow;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class MangaIntentClassifierService {

    private static final List<String> DIRECTOR_KEYWORDS = List.of(
            "导演", "生成分镜", "创建分镜", "做分镜", "画面脚本", "保存分镜", "改分镜", "重写分镜",
            "拆分镜头", "推进制作", "生成 storyboard", "save storyboard", "generate storyboard"
    );
    private static final List<String> REVIEW_KEYWORDS = List.of(
            "质检", "检查", "审核", "评估", "风险", "问题", "哪里不对", "是否合理", "review", "check"
    );
    private static final List<String> HITL_KEYWORDS = List.of(
            "确认", "同意", "继续", "选择", "选第", "就用", "可以执行", "暂停", "等待我", "approve", "confirm"
    );
    private static final List<String> CHAT_KEYWORDS = List.of(
            "普通对话", "进度", "状态", "查看本章", "本章漫画", "完成了吗", "到哪",
            "解释", "为什么", "怎么设计", "架构", "说明", "介绍", "是什么", "如何", "聊聊",
            "progress", "status", "help"
    );

    public MangaIntentResult classify(String message, MangaWorkflowContextSnapshot context) {
        String normalized = normalize(latestUserMessage(message));
        if (normalized.isBlank()) {
            return MangaIntentResult.needsConfirmation("empty", "用户输入为空，无法判断任务意图");
        }

        Score director = score(normalized, DIRECTOR_KEYWORDS, MangaWorkflowRoute.DIRECTOR);
        Score review = score(normalized, REVIEW_KEYWORDS, MangaWorkflowRoute.REVIEW);
        Score hitl = score(normalized, HITL_KEYWORDS, MangaWorkflowRoute.HITL);
        Score chat = score(normalized, CHAT_KEYWORDS, MangaWorkflowRoute.CHAT);
        Score winner = List.of(director, review, hitl, chat).stream()
                .max(Score::compareTo)
                .orElse(chat);

        if (winner.score <= 0) {
            return MangaIntentResult.needsConfirmation("unknown", "没有命中稳定的任务意图关键词");
        }
        double confidence = Math.min(0.95, 0.48 + winner.score * 0.16 + contextBoost(winner.route, context));
        return MangaIntentResult.resolved(
                winner.route,
                winner.route.name().toLowerCase(Locale.ROOT),
                confidence,
                winner.reason
        );
    }

    private double contextBoost(MangaWorkflowRoute route, MangaWorkflowContextSnapshot context) {
        if (context == null) {
            return 0.0;
        }
        if (route == MangaWorkflowRoute.REVIEW && context.sceneCount() > 0) {
            return 0.08;
        }
        if (route == MangaWorkflowRoute.DIRECTOR && context.sceneCount() == 0) {
            return 0.08;
        }
        return 0.0;
    }

    private Score score(String normalized, List<String> keywords, MangaWorkflowRoute route) {
        int score = 0;
        String matched = "";
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
                if (matched.isBlank()) {
                    matched = keyword;
                }
            }
        }
        String reason = score == 0 ? "" : "命中关键词：" + matched;
        return new Score(route, score, reason);
    }

    private String normalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String latestUserMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String[] lines = message.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.regionMatches(true, 0, "user:", 0, 5)) {
                return line.substring(5).trim();
            }
        }
        return message;
    }

    private record Score(MangaWorkflowRoute route, int score, String reason) implements Comparable<Score> {
        @Override
        public int compareTo(Score other) {
            return Integer.compare(this.score, other.score);
        }
    }
}
