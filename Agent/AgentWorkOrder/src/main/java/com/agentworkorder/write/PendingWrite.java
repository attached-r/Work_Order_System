package com.agentworkorder.write;

/**
 * 一次已预览、等待用户确认的写操作。
 * <p>
 * 令牌绑定「会话 + 用户 + 工单 + 动作 + 参数指纹」，确认时逐项比对：
 * 换会话、换用户、换工单、换动作、改参数都会失败。
 *
 * @param token               一次性随机令牌
 * @param sessionId           签发时的会话 id
 * @param userId              签发时的用户 id，可空（匿名预览会被项目一拒绝）
 * @param orderId             目标工单 id
 * @param action              动作名（REVIEW/DISPATCH/...）
 * @param observedFromStatus  预览时工单的实时状态，确认时作为 expectedFromStatus
 * @param paramsFingerprint   动作参数的 SHA-256，防止预览后改参数
 * @param issuedAtMillis      签发时刻
 * @param expiresAtMillis     过期时刻
 */
public record PendingWrite(
        String token,
        String sessionId,
        Long userId,
        Long orderId,
        String action,
        Integer observedFromStatus,
        String paramsFingerprint,
        long issuedAtMillis,
        long expiresAtMillis) {
}
