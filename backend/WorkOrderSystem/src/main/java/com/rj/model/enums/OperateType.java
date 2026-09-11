package com.rj.model.enums;

import lombok.Getter;

/**
 * 工单操作事件(与 work_order_operate_log.operate_type 码值一一对应)。
 * <p>
 * 每次状态流转都会在操作日志表落一行,记录「谁、做了什么、从哪个状态到哪个状态」,
 * 便于审计与问题追溯。
 */
@Getter
public enum OperateType {

    SUBMIT(1, "提交"),
    REVIEW_PASS(2, "审核通过"),
    REVIEW_REJECT(3, "审核驳回"),
    DISPATCH(4, "派单"),
    PROCESS_FINISH(5, "处理完成"),
    ACCEPT_PASS(6, "验收通过"),
    ACCEPT_REJECT(7, "验收退回"),
    TRANSFER(8, "转派"),
    WITHDRAW(9, "撤回/取消"),
    TIMEOUT_CLOSE(10, "超时关闭");

    /** 数据库存储码值 */
    private final Integer code;

    /** 事件中文名 */
    private final String desc;

    OperateType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
