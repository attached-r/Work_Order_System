package com.rj.controller;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.AcceptDTO;
import com.rj.model.dto.DispatchDTO;
import com.rj.model.dto.ProcessDTO;
import com.rj.model.dto.ReviewDTO;
import com.rj.model.dto.WithdrawDTO;
import com.rj.model.dto.WorkOrderCreateDTO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderStatsVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IWorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口(模块二:工单核心业务)
 * <p>
 * 采用「动作型」REST 设计:状态变更通过子资源动词路径表达
 * (/review、/dispatch、/process、/accept、/withdraw),便于挂载各自权限码,
 * 也比「PUT 改 status 字段」更能避免绕过状态机。
 */
@Tag(name = "工单管理")
@RequestMapping("/workorder")
@RestController
@RequiredArgsConstructor
public class WorkOrderController {

    private final IWorkOrderService workOrderService;

    /**
     * 创建(提交)工单,初始进入待审核。
     */
    @Operation(summary = "创建工单")
    @RequiresPermission("workorder:create")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody WorkOrderCreateDTO dto) {
        return Result.success(workOrderService.create(dto));
    }

    /**
     * 工单分页列表,结果按登录人角色自动收敛数据范围。
     */
    @Operation(summary = "工单分页列表")
    @RequiresPermission("workorder:query")
    @GetMapping("/page")
    public Result<PageResult<WorkOrderVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer orderType,
            @RequestParam(required = false) String keyword) {
        return Result.success(workOrderService.page(current, size, status, orderType, keyword));
    }

    /**
     * 工单统计:总数与各状态数量,数据范围与列表一致。
     * 供工作台的指标卡/状态分布与列表页的状态筛选条取数。
     */
    @Operation(summary = "工单统计")
    @RequiresPermission("workorder:query")
    @GetMapping("/stats")
    public Result<WorkOrderStatsVO> stats(
            @RequestParam(required = false) Integer orderType,
            @RequestParam(required = false) String keyword) {
        return Result.success(workOrderService.stats(orderType, keyword));
    }

    /**
     * 工单详情,含资源明细与操作日志。
     */
    @Operation(summary = "工单详情")
    @RequiresPermission("workorder:query")
    @GetMapping("/{id}")
    public Result<WorkOrderDetailVO> detail(@PathVariable Long id) {
        return Result.success(workOrderService.detail(id));
    }

    /**
     * 审核工单:通过 → 待派单;驳回 → 已驳回。
     */
    @Operation(summary = "审核工单")
    @RequiresPermission("workorder:review")
    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id, @Valid @RequestBody ReviewDTO dto) {
        workOrderService.review(id, dto);
        return Result.success();
    }

    /**
     * 派单 / 转派:同一入口,按工单当前状态区分。
     * <p>
     * 此接口<b>刻意不挂 {@code @RequiresPermission}</b>:派单需 {@code workorder:dispatch}、
     * 转派需 {@code workorder:transfer},而注解语义是「需同时满足全部权限」,无法表达二者其一。
     * 因此改在 Service 内按实际流转做精确判权(见 {@code WorkOrderService#dispatch})。
     */
    @Operation(summary = "派单/转派")
    @PostMapping("/{id}/dispatch")
    public Result<Void> dispatch(@PathVariable Long id, @Valid @RequestBody DispatchDTO dto) {
        workOrderService.dispatch(id, dto);
        return Result.success();
    }

    /**
     * 处理完成:处理中 → 待验收。
     */
    @Operation(summary = "处理完成")
    @RequiresPermission("workorder:process")
    @PostMapping("/{id}/process")
    public Result<Void> process(@PathVariable Long id, @Valid @RequestBody ProcessDTO dto) {
        workOrderService.process(id, dto);
        return Result.success();
    }

    /**
     * 验收工单:通过 → 已完成;退回 → 处理中。
     */
    @Operation(summary = "验收工单")
    @RequiresPermission("workorder:accept")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id, @Valid @RequestBody AcceptDTO dto) {
        workOrderService.accept(id, dto);
        return Result.success();
    }

    /**
     * 撤回 / 取消工单(提单人本人)。
     */
    @Operation(summary = "撤回/取消工单")
    @RequiresPermission("workorder:withdraw")
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id, @Valid @RequestBody WithdrawDTO dto) {
        workOrderService.withdraw(id, dto);
        return Result.success();
    }

    /**
     * 重新提交工单:被驳回后由提单人修改内容并重投,回到待审核。
     * <p>
     * 入参复用创建表单 {@link WorkOrderCreateDTO};资源明细非空时覆盖式替换。
     */
    @Operation(summary = "重新提交工单")
    @RequiresPermission("workorder:modify")
    @PostMapping("/{id}/resubmit")
    public Result<Void> resubmit(@PathVariable Long id, @Valid @RequestBody WorkOrderCreateDTO dto) {
        workOrderService.resubmit(id, dto);
        return Result.success();
    }
}
