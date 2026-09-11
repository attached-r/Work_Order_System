package com.rj.job;

import com.rj.service.IWorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工单超时自动关闭定时任务。
 * <p>
 * 周期性扫描「未完结且 expire_time 已过」的工单并置为「7 已超时」。
 * 扫描间隔可用配置 {@code workorder.timeout.scan-delay-ms} 调整,默认 60 秒;
 * 具体扫描与关闭逻辑委托给 {@link IWorkOrderService#closeTimeout()}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkOrderTimeoutJob {

    private final IWorkOrderService workOrderService;

    /**
     * 定时触发:上一轮执行结束后固定延迟再触发下一轮(fixedDelay),
     * 避免扫描耗时较长时任务堆积。
     */
    @Scheduled(fixedDelayString = "${workorder.timeout.scan-delay-ms:60000}")
    public void scanTimeoutOrders() {
        try {
            workOrderService.closeTimeout();
        } catch (Exception e) {
            // 单次失败不应中断后续调度,记录后等下一轮重试
            log.error("工单超时扫描任务执行失败", e);
        }
    }
}
