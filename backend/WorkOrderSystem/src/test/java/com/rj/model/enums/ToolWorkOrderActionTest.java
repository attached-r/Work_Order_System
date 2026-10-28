package com.rj.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工单动作枚举测试（P0 的配套）。
 *
 * <p>动作是写接口的“意图”载体：REVIEW 与 DISPATCH 的前置状态、权限码都不同。
 * 这里锁住三件事：
 * 1. 每个动作允许的起点状态；
 * 2. 每个动作绑定的权限码；
 * 3. 动作名解析对大小写/空格友好，但未知动作必须返回 null（由 Service 转 400）。
 */
class ToolWorkOrderActionTest {

    @Test
    void reviewShouldOnlyAllowPendingReview() {
        assertThat(ToolWorkOrderAction.REVIEW.allowsFrom(0)).isTrue();
        assertThat(ToolWorkOrderAction.REVIEW.allowsFrom(1)).isFalse();
        assertThat(ToolWorkOrderAction.REVIEW.allowsFrom(2)).isFalse();
        assertThat(ToolWorkOrderAction.REVIEW.getPermCode()).isEqualTo("workorder:review");
    }

    @Test
    void dispatchShouldOnlyAllowPendingDispatch() {
        assertThat(ToolWorkOrderAction.DISPATCH.allowsFrom(1)).isTrue();
        assertThat(ToolWorkOrderAction.DISPATCH.allowsFrom(0)).isFalse();
        assertThat(ToolWorkOrderAction.DISPATCH.allowsFrom(2)).isFalse();
        assertThat(ToolWorkOrderAction.DISPATCH.getPermCode()).isEqualTo("workorder:dispatch");
    }

    @Test
    void transferAndProcessShouldOnlyAllowProcessing() {
        assertThat(ToolWorkOrderAction.TRANSFER.allowsFrom(2)).isTrue();
        assertThat(ToolWorkOrderAction.TRANSFER.allowsFrom(1)).isFalse();
        assertThat(ToolWorkOrderAction.TRANSFER.getPermCode()).isEqualTo("workorder:transfer");

        assertThat(ToolWorkOrderAction.PROCESS.allowsFrom(2)).isTrue();
        assertThat(ToolWorkOrderAction.PROCESS.allowsFrom(3)).isFalse();
        assertThat(ToolWorkOrderAction.PROCESS.getPermCode()).isEqualTo("workorder:process");
    }

    @Test
    void acceptShouldOnlyAllowPendingAccept() {
        assertThat(ToolWorkOrderAction.ACCEPT.allowsFrom(3)).isTrue();
        assertThat(ToolWorkOrderAction.ACCEPT.allowsFrom(2)).isFalse();
        assertThat(ToolWorkOrderAction.ACCEPT.getPermCode()).isEqualTo("workorder:accept");
    }

    @Test
    void withdrawShouldAllowAllNonTerminalStarts() {
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(0)).isTrue();
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(1)).isTrue();
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(2)).isTrue();
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(5)).isTrue();

        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(3)).isFalse();
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(4)).isFalse();
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(6)).isFalse();
        assertThat(ToolWorkOrderAction.WITHDRAW.allowsFrom(7)).isFalse();
        assertThat(ToolWorkOrderAction.WITHDRAW.getPermCode()).isEqualTo("workorder:withdraw");
    }

    @Test
    void fromCodeShouldTrimAndIgnoreCase() {
        assertThat(ToolWorkOrderAction.fromCode("review")).isSameAs(ToolWorkOrderAction.REVIEW);
        assertThat(ToolWorkOrderAction.fromCode("  REVIEW  ")).isSameAs(ToolWorkOrderAction.REVIEW);
        assertThat(ToolWorkOrderAction.fromCode("Dispatch")).isSameAs(ToolWorkOrderAction.DISPATCH);
    }

    @Test
    void fromCodeShouldReturnNullForUnknownOrNull() {
        // 返回 null 而不是抛异常：Service 需要统一转成「不支持的动作」400
        assertThat(ToolWorkOrderAction.fromCode("APPROVE")).isNull();
        assertThat(ToolWorkOrderAction.fromCode("")).isNull();
        assertThat(ToolWorkOrderAction.fromCode(null)).isNull();
    }

    @Test
    void allowsFromShouldRejectNullStatus() {
        assertThat(ToolWorkOrderAction.REVIEW.allowsFrom(null)).isFalse();
    }
}
