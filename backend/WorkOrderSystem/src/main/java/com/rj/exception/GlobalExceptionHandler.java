package com.rj.exception;

import com.rj.common.Result;
import com.rj.common.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器,统一捕获异常并转换为标准响应 {@link Result}。
 * 响应体 code 区分业务状态,HTTP 状态码统一为 200。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：状态流转、权限校验等主动抛出。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 请求体参数校验失败：@RequestBody 配合 @Valid / @Validated。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = firstFieldError(e.getBindingResult().getFieldErrors());
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 表单对象绑定校验失败：@ModelAttribute 方式提交。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = firstFieldError(e.getBindingResult().getFieldErrors());
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 方法参数校验失败：@RequestParam / @PathVariable 配合类上的 @Validated。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ResultCode.BAD_REQUEST.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 请求体解析失败：如 JSON 格式错误、请求体为空。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), "请求参数格式错误");
    }

    /**
     * 参数类型不匹配：如路径变量 /users/abc 无法转换为数字。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), "参数类型错误");
    }

    /**
     * 兜底异常：记录日志并返回通用提示,避免向前端泄露内部细节。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 取第一个字段校验错误,格式 field: message
     */
    private String firstFieldError(List<FieldError> errors) {
        return errors.stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ResultCode.BAD_REQUEST.getMessage());
    }
}
