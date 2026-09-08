package com.rj.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结果。
 *
 * @param <T> 业务数据类型
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Result<T> {

    /** 业务状态码 取值见{@link ResultCode} */
    private Integer code; // 使用Integer 是因为 code可能 为null;而int不能为空

    /** 提示信息*/
    private  String message;

    /** 业务数据*/
    private T data;

    /** 响应时间戳(ms)*/
    private Long timestamp;

    /**
     * 成功响应,无数据携带,即无参数传递
     */
    public static <T> Result<T> success() {
        return new Result<>(
                ResultCode.SUCCESS.getCode(),
                ResultCode.SUCCESS.getMessage(),
                null,  // 无数据携带 即为null
                System.currentTimeMillis()
        );
    }

    /**
     * 成功响应,<font color="red">携带数据</font> 函数重载
    */
    public static  <T> Result<T> success(T data) {
        return new Result<>(
                ResultCode.SUCCESS.getCode(),
                ResultCode.SUCCESS.getMessage(),
                data,  // 数据携带
                System.currentTimeMillis()
        );
    }

    /**
     * 失败响应 指定状态码与提示信息
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code,message,null,System.currentTimeMillis());
    }

    /**
     * 失败响应 使用{@link ResultCode} 自带的状态码与提示信息
     */
    public static   <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(
                resultCode.getCode(),
                resultCode.getMessage(),
                null,
                System.currentTimeMillis()
        );
    }
}
