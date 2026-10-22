package com.rj.model.enums;

import lombok.Getter;

import java.util.Set;

/**
 * Agent 写操作的动作白名单(设计文档 M4 §项目一)。
 * <p>
 * <b>为什么用「动作」而不是「目标状态」:</b>目标态 2「处理中」有三条来路——派单(1→2)、
 * 转派(2→2)、验收退回(3→2)。只传 targetStatus 时,调用方预览后若状态被别人改过,
 * 同一份请求可能被路由成另一种动作(典型:把「派单」执行成「转派」)。因此每项显式声明
 * 允许的起点状态 {@link #fromStatuses},由 Service 先比对「最新状态」再分发。
 * <p>
 * 每个动作绑定一个权限码:Agent 侧一个动作一个工具,权限注册表才能精确到动作;
 * 判权在 Service 内按 action 逐个做(Controller 上的注解无法表达「六选一」)。
 */
@Getter
public enum ToolWorkOrderAction {

    /** 审核:通过 → 待派单;驳回 → 已驳回 */
    REVIEW("审核", "workorder:review", Set.of(0)),
    /** 派单:待派单 → 处理中,指定处理人 */
    DISPATCH("派单", "workorder:dispatch", Set.of(1)),
    /** 转派:处理中 → 处理中(自环),更换处理人 */
    TRANSFER("转派", "workorder:transfer", Set.of(2)),
    /** 处理完成:处理中 → 待验收 */
    PROCESS("处理完成", "workorder:process", Set.of(2)),
    /** 验收:通过 → 已完成;退回 → 处理中 */
    ACCEPT("验收", "workorder:accept", Set.of(3)),
    /** 撤回/取消:待审核/待派单/处理中/已驳回 → 已取消 */
    WITHDRAW("撤回/取消", "workorder:withdraw", Set.of(0, 1, 2, 5));

    /** 动作中文名(异常提示与响应回显用) */
    private final String desc;

    /** 执行该动作所需权限码(原样取自项目一权限表,不推导) */
    private final String permCode;

    /** 该动作允许的工单起点状态码 */
    private final Set<Integer> fromStatuses;

    ToolWorkOrderAction(String desc, String permCode, Set<Integer> fromStatuses) {
        this.desc = desc;
        this.permCode = permCode;
        this.fromStatuses = fromStatuses;
    }

    /**
     * 按动作名解析枚举,大小写不敏感并忽略首尾空白;未知动作返回 {@code null}。
     * <p>
     * 返回 null 而不抛异常:调用方(工具 Service)需要把「参数错误」统一转成 400 业务异常,
     * 这里保持纯解析职责。
     */
    public static ToolWorkOrderAction fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (ToolWorkOrderAction action : values()) {
            if (action.name().equalsIgnoreCase(normalized)) {
                return action;
            }
        }
        return null;
    }

    /** 当前状态是否在本动作允许的起点内 */
    public boolean allowsFrom(Integer status) {
        return status != null && fromStatuses.contains(status);
    }
}
