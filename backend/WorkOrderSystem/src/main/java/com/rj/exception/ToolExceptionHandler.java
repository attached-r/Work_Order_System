package com.rj.exception;

import com.rj.common.ResultCode;
import com.rj.model.vo.ToolErrorVO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Tool 接口专用异常处理器:把错误转成精简的 {@link ToolErrorVO},不套标准响应体。
 * <p>
 * 与全局 {@link GlobalExceptionHandler} 并存,但<b>只作用于 {@code com.rj.controller.tool} 包</b>
 * (按 {@code basePackages} 划定),所以现有接口的响应体行为完全不变。
 * <p>
 * <b>两个易错点(改动务必留意):</b>
 * <ol>
 *   <li>{@code @Order(HIGHEST_PRECEDENCE)} 必须保留:处理 /tool 请求时本类与全局处理器同为候选,
 *       默认两者都是 LOWEST_PRECEDENCE,平级只能按注册顺序、结果不确定;显式提高优先级才能保证
 *       工具请求优先落到本类。</li>
 *   <li>下面每个异常都必须逐个声明,不能只留 {@code Exception} 兜底:Spring 只选「按顺序第一个
 *       能解析该异常的 advice」,不做跨 advice 的 specificity 比较。若本类只声明 Exception,
 *       工具侧 @Valid 校验失败会落到本类的 Exception 分支 → 错判为 500。此处逐条声明,正是为了
 *       与 {@link GlobalExceptionHandler} 保持一致的行为。</li>
 * </ol>
 * HTTP 状态码仍统一为 200(与全站约定一致,真实结果看 body 的 code);拦截器在 preHandle
 * 抛出的登录/权限异常此时 HandlerMethod 已解析,同样会被本类命中,工具侧也是精简体。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.rj.controller.tool")
public class ToolExceptionHandler {

    /**
     * 业务异常:状态流转、数据范围校验等主动抛出,code 直接取自异常携带的 {@link ResultCode}。
     */
    @ExceptionHandler(BusinessException.class)
    public ToolErrorVO handleBusinessException(BusinessException e) {
        return new ToolErrorVO(e.getCode(), e.getMessage());
    }

    /**
     * 请求体参数校验失败:@RequestBody 配合 @Valid。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ToolErrorVO handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        return new ToolErrorVO(ResultCode.BAD_REQUEST.getCode(),
                firstFieldError(e.getBindingResult().getFieldErrors()));
    }

    /**
     * 表单对象绑定校验失败:@ModelAttribute 方式提交。
     */
    @ExceptionHandler(BindException.class)
    public ToolErrorVO handleBindException(BindException e) {
        return new ToolErrorVO(ResultCode.BAD_REQUEST.getCode(),
                firstFieldError(e.getBindingResult().getFieldErrors()));
    }

    /**
     * 方法参数校验失败:@RequestParam / @PathVariable 配合类上的 @Validated。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ToolErrorVO handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ResultCode.BAD_REQUEST.getMessage());
        return new ToolErrorVO(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 请求体解析失败:如 JSON 格式错误、请求体为空。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ToolErrorVO handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return new ToolErrorVO(ResultCode.BAD_REQUEST.getCode(), "请求参数格式错误");
    }

    /**
     * 参数类型不匹配:如路径变量无法转换为数字。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ToolErrorVO handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        return new ToolErrorVO(ResultCode.BAD_REQUEST.getCode(), "参数类型错误");
    }

    /**
     * 兜底异常:记日志并返回通用提示,避免把内部细节暴露给 Agent/大模型。
     */
    @ExceptionHandler(Exception.class)
    public ToolErrorVO handleException(Exception e) {
        log.error("工具接口异常", e);
        return new ToolErrorVO(ResultCode.INTERNAL_SERVER_ERROR.getCode(),
                ResultCode.INTERNAL_SERVER_ERROR.getMessage());
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
