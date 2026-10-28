package com.rj.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单状态机测试（P0）。
 *
 * <p>为什么用「全量矩阵」而不是只挑几条边：
 * {@link WorkOrderStatus#TRANSITIONS} 是一张手写表，最容易出的问题不是“某条边写错”，
 * 而是“新增状态后忘了补边”或“把 2→2 的自环删掉”。全量 8×8 矩阵能把这些错误一次性锁住。
 *
 * <p>测试只依赖枚举本身，不启动 Spring，也不连数据库；毫秒级完成。
 */
class WorkOrderStatusTest {

    /**
     * 设计文档里允许的迁移边。
     * 字符串格式 "from->to"，用状态码而不是枚举名，读起来更接近数据库里的值。
     */
    private static final Set<String> LEGAL_EDGES = Set.of(
            // 0 待审核：审核通过 / 驳回 / 撤回 / 超时
            "0->1", "0->5", "0->6", "0->7",
            // 1 待派单：派单 / 撤回 / 超时
            "1->2", "1->6", "1->7",
            // 2 处理中：转派自环 / 处理完成 / 撤回 / 超时
            "2->2", "2->3", "2->6", "2->7",
            // 3 待验收：验收通过 / 退回返工 / 超时
            "3->4", "3->2", "3->7",
            // 5 已驳回：重新提交 / 撤回
            "5->0", "5->6"
            // 4/6/7 是终态，没有任何出边
    );

    @Test
    void transitionMatrixShouldMatchTheSpec() {
        for (WorkOrderStatus from : WorkOrderStatus.values()) {
            for (WorkOrderStatus to : WorkOrderStatus.values()) {
                String edge = from.getCode() + "->" + to.getCode();
                boolean expected = LEGAL_EDGES.contains(edge);

                assertThat(from.canTransitionTo(to))
                        .as("迁移 %s 是否为合法边", edge)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void nullTargetShouldBeRejected() {
        // 目标状态为空说明调用方传参有问题，不能因为 Set.contains(null) 的偶然行为放行
        assertThat(WorkOrderStatus.PENDING_REVIEW.canTransitionTo(null)).isFalse();
    }

    @Test
    void onlyCompletedCanceledAndTimeoutAreTerminal() {
        assertThat(WorkOrderStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(WorkOrderStatus.CANCELED.isTerminal()).isTrue();
        assertThat(WorkOrderStatus.TIMEOUT.isTerminal()).isTrue();

        assertThat(WorkOrderStatus.PENDING_REVIEW.isTerminal()).isFalse();
        assertThat(WorkOrderStatus.PENDING_DISPATCH.isTerminal()).isFalse();
        assertThat(WorkOrderStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(WorkOrderStatus.PENDING_ACCEPT.isTerminal()).isFalse();
        assertThat(WorkOrderStatus.REJECTED.isTerminal()).isFalse();
    }

    @Test
    void fromCodeShouldResolveAllKnownCodes() {
        for (WorkOrderStatus status : WorkOrderStatus.values()) {
            assertThat(WorkOrderStatus.fromCode(status.getCode())).isSameAs(status);
        }
    }

    @Test
    void fromCodeShouldRejectUnknownOrNullCode() {
        assertThatThrownBy(() -> WorkOrderStatus.fromCode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkOrderStatus.fromCode(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> WorkOrderStatus.fromCode(8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }
}
