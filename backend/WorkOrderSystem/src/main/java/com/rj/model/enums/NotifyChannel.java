package com.rj.model.enums;

import lombok.Getter;

/**
 * 通知渠道(与 notification.channel 码值一一对应)。
 * <p>
 * 本期只落 {@link #IN_APP}:载荷、拓扑、消费逻辑都不参与渠道决策,
 * 将来扩展邮件/短信只需在消费端加一个分支 + 一个发送适配器(见设计文档 11.7)。
 */
@Getter
public enum NotifyChannel {

    IN_APP(1, "站内信"),
    EMAIL(2, "邮件"),
    SMS(3, "短信");

    /** 数据库存储码值 */
    private final Integer code;

    /** 渠道中文名 */
    private final String desc;

    NotifyChannel(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
