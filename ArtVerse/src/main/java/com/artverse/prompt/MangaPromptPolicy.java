package com.artverse.prompt;

import java.util.List;
import java.util.regex.Pattern;

public final class MangaPromptPolicy {

    private MangaPromptPolicy() {
    }

    public static String storyboardInstruction(int pageCount) {
        return """

                【ArtVerse漫画分镜生产规范】
                请把正文改写为连续漫画页分镜。输出必须能直接用于图片生成，不要写成单张海报描述。
                你可能会看到类似“Scene 1｜全景”“负面：禁止...”的单图提示词，请把它们改写成漫画页内的多个分镜格，不要原样保留。

                硬性要求：
                - 输出 JSON 数组，恰好 %d 个字符串元素；每个元素代表一页漫画，不是一格画面。
                - 每页必须包含 4-6 个分镜格，并用【第1格】【第2格】这样的标记书写。
                - 每格都要写清楚景别、构图、人物、动作、表情、环境、光影。
                - 如果原剧情确实无人说话，也必须用“旁白框：「...」”或环境音效补足可读叙事；有人物互动时，每页至少包含 2 条简体中文对白气泡。
                - 对白格式为：对话气泡：「角色：台词」。旁白格式为：旁白框：「内容」。
                - 动作或转场页要包含简体中文或无语言音效字，例如：唰、铿、轰、嗡。
                - 镜头节奏要有变化：远景、中景、近景、特写交替出现。
                - 人物服装、外貌、道具和场景必须前后连续一致。
                - 禁止英文对白、繁体字、罗马字、页码、标题、水印、作者署名和解释性说明。
                - 禁止出现 Scene、Panel、Prompt、Negative、负面、禁止列表、2:3、镜头参数、模型参数。
                - 禁止把“墨法、光线、氛围、构图要点、负面词”作为独立字段；只能自然融合进每格画面描述。
                - 不要输出 Markdown，不要输出表格，不要输出代码块，只输出 JSON 数组。

                示例格式：
                [
                  "第1页：【第1格（远景）】... 对话气泡：「角色A：简体中文台词」。【第2格（近景）】... 音效字：唰。【第3格（特写）】...【第4格（中景）】...",
                  "第2页：【第1格（大宽格）】...【第2格（窄格）】...【第3格（特写）】...【第4格（中景）】..."
                ]
                """.formatted(pageCount);
    }

    public static boolean isStoryboardPage(String scene) {
        return scene != null
                && scene.contains("【第1格")
                && scene.contains("【第2格")
                && scene.contains("【第3格")
                && scene.contains("【第4格");
    }

    public static boolean hasForbiddenStoryboardCue(String scene) {
        if (scene == null) return false;
        return Pattern.compile("\\b(scene|panel|prompt|negative|page|chapter|dialogue|bubble|speech)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(scene)
                .find()
                || scene.contains("负面：")
                || scene.contains("禁止列表")
                || scene.contains("竖构图2:3");
    }

    public static String buildImagePrompt(String scene, String profiles, String mangaStyle, String colorMode,
                                          boolean hasRefImages, List<String> allScenes, int imageNumber) {
        int totalPages = allScenes == null || allScenes.isEmpty() ? 1 : allScenes.size();
        StringBuilder sb = new StringBuilder();

        sb.append("你正在绘制一部连续漫画作品的第").append(imageNumber)
                .append("页，共").append(totalPages).append("页。\n");
        sb.append("目标不是单张海报，而是一页可阅读的多格漫画页面。\n\n");

        if (hasRefImages) {
            sb.append("【人物一致性】\n");
            sb.append("本次提供了角色参考图。必须严格保持参考图中的主角外貌特征，")
                    .append("包括发型、发色、脸型、五官比例、服装风格和标志性道具。")
                    .append("所有分镜格中的人物都应是同一批人物，禁止凭空创造新外貌。\n\n");
        } else if (profiles != null && !profiles.isBlank()) {
            sb.append("【角色设定】\n");
            sb.append(profiles).append("\n\n");
        }

        sb.append("【完整分镜上下文】\n");
        if (allScenes != null && !allScenes.isEmpty()) {
            for (int i = 0; i < allScenes.size(); i++) {
                sb.append("第").append(i + 1).append("页：").append(allScenes.get(i)).append("\n");
            }
        } else {
            sb.append("第1页：").append(scene).append("\n");
        }
        sb.append("\n");

        sb.append("【当前只绘制这一页】\n");
        sb.append(scene).append("\n\n");

        sb.append("【漫画页绘制要求】\n");
        sb.append("- 竖向漫画页构图，页面内必须有 4-6 个清晰分镜格，格子之间有明确边框。\n");
        sb.append("- 每格画面要承接剧情动作，形成连续叙事；不要把人物摆成单幅宣传海报。\n");
        sb.append("- 保留分镜中的对白气泡和音效字，但所有可读文字必须是简体中文。\n");
        sb.append("- 对话气泡要自然嵌入画面，文字短而清晰；禁止英文、繁体字、乱码、伪文字、罗马字。\n");
        sb.append("- 禁止出现页码、编号、分数、标题、水印、签名、Logo、UI按钮或任何排版标记。\n");
        sb.append("- 人物外貌、服装、道具、场景空间、光影方向必须与整章上下文保持一致。\n");
        sb.append("- 画面节奏要有远景、中景、近景、特写变化，动作线和视线方向要推动阅读。\n\n");

        sb.append("【画风】\n");
        sb.append(styleTemplate(mangaStyle)).append("\n");
        sb.append(colorModifier(colorMode)).append("\n");

        return sb.toString();
    }

    private static String styleTemplate(String mangaStyle) {
        return switch (mangaStyle == null ? "japanese_manga" : mangaStyle) {
            case "korean_webtoon" -> "韩式条漫气质，干净线条，自然渐变光影，角色比例修长，适合竖屏阅读，但当前输出仍必须是一页多格漫画。";
            case "american_comic" -> "美式漫画风格，粗重有力的线条，高对比阴影，动态透视强，动作冲击力强；拟声词仍必须使用简体中文。";
            case "ligne_claire" -> "欧式清线漫画风格，线条均匀清晰，平涂色块，背景细节明确，叙事清楚。";
            case "chinese_ink" -> "中国水墨国风漫画，水墨渲染、工笔线条、留白构图和传统场景元素结合，但分镜边框和阅读顺序必须清晰。";
            case "semi_realistic" -> "半厚涂写实漫画风格，角色细腻、材质明确、光影自然，同时保留漫画分格和气泡。";
            case "realistic" -> "写实漫画风格，真实光影和人物比例，电影感构图，但必须保持多格漫画页结构。";
            case "oil_painting" -> "厚涂油画漫画风格，笔触丰富、色彩层次强，同时保留清晰漫画分格。";
            case "flat_design" -> "扁平极简漫画风格，形状清楚、色块干净、信息可读，分镜结构明确。";
            case "pixel_art" -> "像素风漫画，像素颗粒清楚，复古游戏感，但仍是多格漫画页。";
            case "watercolor" -> "水彩淡雅漫画风格，柔和透明的色彩和纸张质感，分镜边界清晰。";
            case "cyberpunk" -> "赛博朋克漫画风格，霓虹光、城市科技感、高对比色彩，但文字必须为简体中文。";
            default -> "日式漫画风格，精细线条、网点阴影、戏剧性光影、清晰多格分镜、表情和动作生动。";
        };
    }

    private static String colorModifier(String colorMode) {
        return switch (colorMode == null ? "bw" : colorMode) {
            case "color" -> "色彩模式：全彩，高饱和但不脏乱，角色和背景层次分明。";
            case "grayscale" -> "色彩模式：灰度，保留丰富中间灰阶和素描质感。";
            case "duotone" -> "色彩模式：双色调，使用有限色彩制造强烈情绪氛围。";
            default -> "色彩模式：黑白，高对比线稿、网点纸纹、清晰明暗层次。";
        };
    }
}
