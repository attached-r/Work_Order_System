package com.rj.common;

import lombok.Getter;
/**
 * 枚举类 自定义业务状态响应码
 * <p>
 * 写入响应体的 code字段 和 http 响应码相互独立
 */
@Getter
public enum ResultCode {

    SUCCESS(200,"success"),
    BAD_REQUEST(400,"参数错误"),
    UNAUTHORIZED(401,"未登录或已过期"),
    FORBIDDEN(403,"无权限"),
    NOT_FOUND(404,"数据不存在"),
    CONFLICT(409,"业务冲突"),
    INTERNAL_SERVER_ERROR(500,"系统异常，请稍后再试");

    private final int code;
    private final  String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
