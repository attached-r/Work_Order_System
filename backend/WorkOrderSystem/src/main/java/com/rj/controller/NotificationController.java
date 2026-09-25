package com.rj.controller;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.model.vo.NotificationVO;
import com.rj.service.INotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信收件箱接口(模块三:异步通知)。
 * <p>
 * <strong>刻意不挂 {@code @RequiresPermission},登录即可</strong>,理由有三:
 * <ol>
 *   <li><strong>越权在结构上不可能。</strong>{@code user_id} 强制取自
 *       {@link com.rj.common.UserContext}(服务端 ThreadLocal),客户端能影响的只有一个 {@code id},
 *       而 {@code id} 的查询条件永远是 {@code id AND user_id}。
 *       没有权限模型比「查询条件里写死了归属」更安全。</li>
 *   <li><strong>权限码的维护成本是纯仪式。</strong>加一个 {@code notification:query} 要改
 *       {@code data.sql} 种权限行、给 5 个角色补授权、再让所有在线会话的鉴权快照失效——
 *       三步做完,安全性一点没变。</li>
 *   <li><strong>有既有先例。</strong>{@code GET /user/directory}、{@code GET /department/directory}
 *       同样只要求登录。</li>
 * </ol>
 * 安全边界仍由 {@code LoginInterceptor} 保证:{@code /notification/**} 落在 {@code /**} 拦截范围内。
 * <p>
 * 四个接口统一返回 {@code Result<T>},HTTP 状态码恒为 200,业务状态在 body 的 {@code code}。
 */
@Tag(name = "通知中心")
@RequestMapping("/notification")
@RestController
@RequiredArgsConstructor
@Validated
public class NotificationController {

    private final INotificationService notificationService;

    /**
     * 分页收件箱,只返回当前登录用户自己的通知。
     *
     * @param current  页码,默认 1
     * @param size     每页条数,默认 10
     * @param readFlag 不传=全部;0=仅未读;1=仅已读
     */
    @Operation(summary = "分页收件箱")
    @GetMapping("/page")
    public Result<PageResult<NotificationVO>> page(@RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") long current,
                                                   @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页条数必须大于0") long size,
                                                   @RequestParam(required = false) Integer readFlag) {
        return Result.success(notificationService.page(current, size, readFlag));
    }

    /**
     * 未读数,用于顶部红点。走 Redis 缓存,未命中回源计数。
     */
    @Operation(summary = "未读通知数")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.unreadCount());
    }

    /**
     * 标记单条已读(幂等,重复标记仍返回成功且不会覆盖首次已读时间)。
     * <p>
     * 通知不存在<strong>或不属于当前用户</strong>时返回 404 而非 403——
     * 403 等于承认「这条通知存在,只是不归你」,那是存在性泄漏。
     */
    @Operation(summary = "标记已读")
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.success();
    }

    /**
     * 全部标记已读。
     *
     * @return 本次实际标记的条数(已全部为已读时为 0)
     */
    @Operation(summary = "全部已读")
    @PostMapping("/read-all")
    public Result<Integer> markAllRead() {
        return Result.success(notificationService.markAllRead());
    }
}
