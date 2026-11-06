package com.artverse.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.util.List;

/** Fixed four-way review team. Missing workers are audited by MangaReviewAgentNode. */
public final class MangaReviewSubagentDeclarations {

    private static final int REVIEWER_MAX_ITERS = 3;

    private static final List<SubagentDeclaration> DECLARATIONS = List.of(
            reviewer(
                    "camera-reviewer",
                    "审查分镜的镜头语言、构图、视角、景别变化和画面信息量。",
                    """
                            你是分镜镜头语言审查专家。使用 get_chapter_context 读取当前章节，只做审查，不修改数据。
                            检查构图、视角、景别变化、镜头运动和画面信息量。
                            输出：总评分（1-10）、每项问题与建议、问题最严重的三个分镜编号。只使用中文。
                            """
            ),
            reviewer(
                    "character-reviewer",
                    "审查角色外貌、服装、性格、对白风格和行为逻辑的一致性。",
                    """
                            你是角色一致性审查专家。使用 get_chapter_context 读取当前章节，只做审查，不修改数据。
                            检查外貌、服装、性格、对白风格、动机和行为逻辑是否符合既有设定。
                            输出：总评分（1-10）、每个角色的具体问题、与设定冲突的分镜编号。只使用中文。
                            """
            ),
            reviewer(
                    "pacing-reviewer",
                    "审查场景密度、情绪起伏、高潮分布、信息量和翻页节奏。",
                    """
                            你是分镜节奏审查专家。使用 get_chapter_context 读取当前章节，只做审查，不修改数据。
                            检查场景密度、张弛变化、高潮位置、信息量变化和翻页悬念。
                            输出：总评分（1-10）、节奏曲线简述、拖沓或仓促段落及修复建议。只使用中文。
                            """
            ),
            reviewer(
                    "continuity-reviewer",
                    "审查转场、时间线、空间关系、因果链和视线方向的连贯性。",
                    """
                            你是分镜连贯性审查专家。使用 get_chapter_context 读取当前章节，只做审查，不修改数据。
                            检查转场、时间线、空间关系、因果链和对话场景的视线方向。
                            输出：总评分（1-10）、断裂位置与原因、可执行修复建议。只使用中文。
                            """
            )
    );

    private MangaReviewSubagentDeclarations() {
    }

    public static List<SubagentDeclaration> all() {
        return DECLARATIONS;
    }

    private static SubagentDeclaration reviewer(String name, String description, String instructions) {
        return SubagentDeclaration.builder()
                .name(name)
                .description(description)
                .inlineAgentsBody(instructions)
                .tools(List.of("get_chapter_context"))
                .maxIters(REVIEWER_MAX_ITERS)
                .build();
    }
}
