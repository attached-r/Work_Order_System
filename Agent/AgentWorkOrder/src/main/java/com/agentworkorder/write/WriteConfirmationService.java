package com.agentworkorder.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预览确认令牌服务（M4，单实例内存实现）。
 * <p>
 * 两阶段写：第一次调用写工具只做预览并签发令牌；用户确认后带令牌再次调用，
 * 令牌校验通过才真正请求项目一。它防的是「跳过预览、改参数、重放令牌」，
 * <b>不是</b>用户同意的密码学证明——真正的安全边界仍是项目一的权限与状态机。
 * <p>
 * 生命周期：{@link #consume} 取出即删除（一次性）；过期、跨会话、跨用户、跨工单、
 * 换动作、改参数一律 409。M5 换 Redis 时保持本类对外方法不变。
 */
@Slf4j
@Service
public class WriteConfirmationService {

    private final WriteProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 令牌 → 待确认写操作。单实例假设；多实例部署需换 Redis。 */
    private final ConcurrentHashMap<String, PendingWrite> pendingWrites = new ConcurrentHashMap<>();

    public WriteConfirmationService(WriteProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 签发一次性令牌。
     *
     * @param sessionId          当前会话
     * @param userId             当前用户，可空
     * @param orderId            目标工单
     * @param action             动作名
     * @param observedFromStatus 预览时读到的最新状态
     * @param params             动作参数（handlerId/approved/remark），用于指纹
     * @return 令牌
     * @throws WriteRejectedException 待确认预览过多
     */
    public String issue(String sessionId, Long userId, Long orderId, String action,
                        Integer observedFromStatus, Map<String, Object> params) {
        cleanupExpired();
        if (pendingWrites.size() >= properties.getMaxPreviewEntries()) {
            throw new WriteRejectedException(409, "待确认的预览过多，请稍后重试");
        }

        long now = System.currentTimeMillis();
        PendingWrite pending = new PendingWrite(
                randomToken(), sessionId, userId, orderId, action, observedFromStatus,
                fingerprint(params), now, now + properties.getConfirmTtlSeconds() * 1000L);
        pendingWrites.put(pending.token(), pending);
        log.info("签发写确认令牌：session={} user={} order={} action={} 待确认={}",
                sessionId, userId, orderId, action, pendingWrites.size());
        return pending.token();
    }

    /**
     * 校验并消费令牌（取出即删除，重放必然失败）。
     *
     * @param token     用户/模型带回的令牌
     * @param sessionId 当前会话
     * @param userId    当前用户
     * @param action    当前工具对应的动作
     * @param orderId   当前请求的工单
     * @param params    本次提交的动作参数
     * @return 待确认写操作（含 observedFromStatus）
     * @throws WriteRejectedException 令牌无效或参数不一致
     */
    public PendingWrite consume(String token, String sessionId, Long userId, String action,
                                Long orderId, Map<String, Object> params) {
        cleanupExpired();
        if (!StringUtils.hasText(token)) {
            throw invalidToken();
        }

        // 先删除再校验：无论后续校验是否通过，这个令牌都只能用一次
        PendingWrite pending = pendingWrites.remove(token);
        if (pending == null) {
            throw invalidToken();
        }

        boolean sameOwner = Objects.equals(pending.sessionId(), sessionId)
                && Objects.equals(pending.userId(), userId)
                && Objects.equals(pending.action(), action)
                && Objects.equals(pending.orderId(), orderId);
        if (!sameOwner || pending.expiresAtMillis() <= System.currentTimeMillis()) {
            throw invalidToken();
        }

        // 参数指纹不一致单独提示：用户能明确知道「确认的内容和预览的不一样」
        if (!pending.paramsFingerprint().equals(fingerprint(params))) {
            throw new WriteRejectedException(409, "确认参数与预览不一致，请重新预览");
        }
        return pending;
    }

    /** 统一话术，不透露「过期 / 跨会话 / 不存在」的具体原因，防探测 */
    private static WriteRejectedException invalidToken() {
        return new WriteRejectedException(409, "确认已失效，请重新预览");
    }

    /**
     * 参数指纹：按键名排序后序列化再取 SHA-256。
     * <p>
     * 必须排序，否则 Map 的遍历顺序会让「同样的参数」算出不同指纹。
     */
    private String fingerprint(Map<String, Object> params) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(params));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // 参数都是简单类型，理论上不会失败；失败时按不可用处理（fail-closed）
            throw new WriteRejectedException(500, "无法计算确认参数指纹，请重新预览");
        }
    }

    /** 256 位随机令牌；URL 安全字符集，避免放进对话参数时需要转义 */
    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 顺手清理过期项：签发与校验都是低频操作，无需额外定时任务 */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        pendingWrites.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }
}
