package com.fishsunny.assistant.engine.tool.service.review;

/**
 * AI 安全审查判定结果。
 * <p>
 * 替代原先 DangerChecker 只返回 true/false 的裸布尔：现在审查（子 Agent）返回
 * {@code {isDanger, reason}}，工具保持"命中即按各自 mode 处理"的行为不变，但能把
 * reason（危险点说明）透传给前端的 ask 确认窗口，让用户知道具体风险是什么。
 *
 * @param isDanger 该操作是否被判定为危险
 * @param reason   危险原因 / 风险说明；安全时通常为空字符串
 */
public record ReviewResult(boolean isDanger, String reason) {

    public static ReviewResult safe() {
        return new ReviewResult(false, "");
    }

    public static ReviewResult danger(String reason) {
        return new ReviewResult(true, reason == null ? "" : reason);
    }

    /**
     * 生成 alwaysRejectDanger 模式下的拒绝异常信息；有 reason 时带上原因。
     *
     * @param actionDesc 操作描述，如 "此文件写入操作存在危险"
     * @param reason     AI 审查给出的一句话风险原因（可空）
     */
    public static String rejectMessage(String actionDesc, String reason) {
        if (reason != null && !reason.isEmpty()) {
            return "AI 判定" + actionDesc + "，操作被拒绝。原因：" + reason;
        }
        return "AI 判定" + actionDesc + "，操作被拒绝";
    }

    /**
     * 生成确认窗口里的 AI 风险提示 markdown 片段；reason 为空时返回空串（不展示）。
     * 各工具的 ask() 在构建消息时把返回值拼在合适位置即可。
     */
    public static String riskReasonBlock(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        return "> ⚠️ **AI 风险提示**：" + reason + "\n\n";
    }
}
