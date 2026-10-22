package com.agentworkorder.client;

/**
 * 项目一 {@code PUT /tool/workorder/{id}/status} 的请求体。
 * <p>
 * 契约要点:动作 `action` 与「调用方预览时看到的状态」`expectedFromStatus` 必须同时给出。
 * 项目一据此拒绝「状态已变化」的提交,避免把派单悄悄执行成转派。
 * <p>
 * 不用的字段传 {@code null} 即可,项目一按动作判定必填项。
 *
 * @param action             动作:REVIEW/DISPATCH/TRANSFER/PROCESS/ACCEPT/WITHDRAW
 * @param expectedFromStatus 预览时观察到的状态码
 * @param handlerId          派单/转派的目标处理人,其余动作为 null
 * @param approved           审核/验收结论,其余动作为 null
 * @param remark             备注/原因,可空
 */
public record ToolChangeStatusRequest(
        String action,
        Integer expectedFromStatus,
        Long handlerId,
        Boolean approved,
        String remark) {
}
