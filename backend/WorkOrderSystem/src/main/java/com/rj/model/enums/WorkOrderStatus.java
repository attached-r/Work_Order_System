package com.rj.model.enums;

import lombok.Getter;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态机(与 work_order.status 码值一一对应)。
 * <p>
 * 状态流转:0 待审核 → 1 待派单 → 2 处理中 → 3 待验收 → 4 已完成;
 * 另有 5 已驳回(可重回 0)、6 已取消、7 已超时 三个分支状态。
 * <p>
 * 合法迁移集中在 {@link #TRANSITIONS} 定义,任何状态变更前都先调
 * {@link #canTransitionTo(WorkOrderStatus)} 校验,非法迁移直接抛业务异常,
 * 避免「状态被绕过流程改坏」。
 */
@Getter
public enum WorkOrderStatus {

    PENDING_REVIEW(0, "待审核"),
    PENDING_DISPATCH(1, "待派单"),
    PROCESSING(2, "处理中"),
    PENDING_ACCEPT(3, "待验收"),
    COMPLETED(4, "已完成"),
    REJECTED(5, "已驳回"),
    CANCELED(6, "已取消"),
    TIMEOUT(7, "已超时");

    /** 数据库存储码值 */
    private final Integer code;

    /** 状态中文名(用于日志与异常提示) */
    private final String desc;

    WorkOrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 允许迁移表:key 为当前状态,value 为「从该状态可以直接到达」的状态集合。
     * <p>
     * 终态(4/6/7)为空集,表示不再允许任何流转。
     * 注意 2 → 2 是合法自环:转派时状态仍停留在「处理中」,只换处理人。
     */
    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> TRANSITIONS = Map.of(
            PENDING_REVIEW, Set.of(PENDING_DISPATCH, REJECTED, CANCELED, TIMEOUT),
            PENDING_DISPATCH, Set.of(PROCESSING, CANCELED, TIMEOUT),
            PROCESSING, Set.of(PROCESSING, PENDING_ACCEPT, CANCELED, TIMEOUT),
            PENDING_ACCEPT, Set.of(COMPLETED, PROCESSING, TIMEOUT),
            REJECTED, Set.of(PENDING_REVIEW, CANCELED),
            COMPLETED, Set.of(),
            CANCELED, Set.of(),
            TIMEOUT, Set.of()
    );

    /**
     * 判断能否从当前状态迁移到目标状态。
     *
     * @param target 目标状态
     * @return true 表示该迁移被状态机允许
     */
    public boolean canTransitionTo(WorkOrderStatus target) {
        return target != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 是否为终态(已完成 / 已取消 / 已超时),终态工单不允许再做任何动作 */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == TIMEOUT;
    }

    /**
     * 按码值反查枚举。
     *
     * @param code 数据库中的状态码值
     * @return 对应枚举
     * @throws IllegalArgumentException 码值非法(说明数据被外部改坏,属不可恢复异常)
     */
    public static WorkOrderStatus fromCode(Integer code) {
        for (WorkOrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的工单状态码: " + code);
    }
}
