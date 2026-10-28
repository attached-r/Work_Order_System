package com.agentworkorder.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写确认令牌单测（P0）。
 *
 * <p>两阶段写的安全语义全在这一个小类里：
 * <ul>
 *   <li>令牌一次性：取出即删除，重放必须失败；</li>
 *   <li>令牌绑定：会话 / 用户 / 工单 / 动作 / 参数指纹任一不同都拒绝；</li>
 *   <li>过期：过期令牌即使还在 Store 里也不能用；</li>
 *   <li>上限：进程内 Store 有容量保护。</li>
 * </ul>
 *
 * <p>用真实 {@link ObjectMapper} + 内存 Store，不启动 Spring；Redis 版由集成测试覆盖。
 */
class WriteConfirmationServiceTest {

    private static final Map<String, Object> PARAMS = Map.of(
            "handlerId", 7L,
            "approved", true,
            "remark", "同意"
    );

    private WriteProperties properties;
    private MemoryPendingWriteStore store;
    private WriteConfirmationService service;

    @BeforeEach
    void setUp() {
        properties = new WriteProperties();
        properties.setConfirmTtlSeconds(300);
        properties.setMaxPreviewEntries(2000);

        store = new MemoryPendingWriteStore();
        service = new WriteConfirmationService(properties, new ObjectMapper(), store);
    }

    @Test
    void shouldIssueAndConsumeWithSameBinding() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);

        PendingWrite pending = service.consume(
                token, "s1", 1L, "REVIEW", 123L, PARAMS);

        assertThat(pending).isNotNull();
        assertThat(pending.token()).isEqualTo(token);
        assertThat(pending.observedFromStatus()).isEqualTo(0);
        assertThat(pending.expiresAtMillis()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void tokenShouldBeOneTimeOnly() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);

        // 第一次成功，令牌被删除
        assertThat(service.consume(token, "s1", 1L, "REVIEW", 123L, PARAMS)).isNotNull();

        // 第二次必须失败，不能重放
        assertRejected(() -> service.consume(token, "s1", 1L, "REVIEW", 123L, PARAMS));
    }

    @Test
    void shouldRejectDifferentSession() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);
        assertRejected(() -> service.consume(token, "s2", 1L, "REVIEW", 123L, PARAMS));
    }

    @Test
    void shouldRejectDifferentUser() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);
        assertRejected(() -> service.consume(token, "s1", 2L, "REVIEW", 123L, PARAMS));
    }

    @Test
    void shouldRejectDifferentOrder() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);
        assertRejected(() -> service.consume(token, "s1", 1L, "REVIEW", 456L, PARAMS));
    }

    @Test
    void shouldRejectDifferentAction() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);
        assertRejected(() -> service.consume(token, "s1", 1L, "DISPATCH", 123L, PARAMS));
    }

    @Test
    void shouldRejectChangedParams() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);

        Map<String, Object> changed = Map.of(
                "handlerId", 7L,
                "approved", false,   // 预览是 true，确认改成 false
                "remark", "同意"
        );

        assertRejected(() -> service.consume(token, "s1", 1L, "REVIEW", 123L, changed));
    }

    @Test
    void failedConsumeShouldStillBurnToken() {
        String token = issue("s1", 1L, 123L, "REVIEW", 0);

        // 先故意用错误参数触发 409
        assertRejected(() -> service.consume(
                token, "s1", 1L, "REVIEW", 123L,
                Map.of("handlerId", 7L, "approved", false, "remark", "不同意")));

        // 令牌已经被删除：即使用正确参数也不能再用
        assertRejected(() -> service.consume(token, "s1", 1L, "REVIEW", 123L, PARAMS));
    }

    @Test
    void expiredTokenShouldBeRejected() {
        long now = System.currentTimeMillis();
        PendingWrite expired = new PendingWrite(
                "expired-token", "s1", 1L, 123L, "REVIEW", 0,
                "any-fingerprint", now - 10_000L, now - 1L);
        store.save(expired, Duration.ZERO);

        assertRejected(() -> service.consume(
                "expired-token", "s1", 1L, "REVIEW", 123L, PARAMS));
    }

    @Test
    void shouldRejectIssueWhenStoreIsFull() {
        properties.setMaxPreviewEntries(1);

        issue("s1", 1L, 100L, "REVIEW", 0);

        assertThatThrownBy(() -> issue("s2", 1L, 101L, "REVIEW", 0))
                .isInstanceOf(WriteRejectedException.class)
                .satisfies(e -> assertThat(((WriteRejectedException) e).getCode()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private String issue(String sessionId, Long userId, Long orderId,
                         String action, Integer observedFromStatus) {
        return service.issue(sessionId, userId, orderId, action, observedFromStatus, PARAMS);
    }

    private static void assertRejected(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(WriteRejectedException.class)
                .satisfies(e -> assertThat(((WriteRejectedException) e).getCode()).isEqualTo(409));
    }
}
