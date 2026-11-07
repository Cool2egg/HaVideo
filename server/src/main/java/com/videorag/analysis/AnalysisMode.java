package com.videorag.analysis;

public enum AnalysisMode {
    GENERAL,
    LEARNING,
    REVIEW,
    CREATION;

    public static AnalysisMode fromRequest(String value) {
        if (value == null || value.isBlank()) return GENERAL;
        try {
            return AnalysisMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            return GENERAL;
        }
    }

    public String instruction() {
        return switch (this) {
            case GENERAL -> "通用模式：提炼核心结论、关键依据与可执行建议。";
            case LEARNING -> "学习模式：整理知识大纲、重点难点、自测题与易错点。";
            case REVIEW -> "审查模式：检查逻辑漏洞、存疑结论、证据不足之处，并给出追问清单。";
            case CREATION -> "创作模式：提取爆点、标题、口播脚本与可复用金句。";
        };
    }

    public String outputHint() {
        return switch (this) {
            case GENERAL -> "sections 放结论与建议；extras 可为空对象。";
            case LEARNING -> "sections 放知识大纲；extras 放 quiz（[{question,answer}]）与 pitfalls（数组）。";
            case REVIEW -> "sections 放问题与依据；extras 放 risks（数组）与 followUps（数组）。";
            case CREATION -> "sections 放内容结构；extras 放 titles（数组）、script（数组）与 highlights（数组）。";
        };
    }
}
