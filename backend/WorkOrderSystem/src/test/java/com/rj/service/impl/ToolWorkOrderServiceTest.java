package com.rj.service.impl;

import com.rj.common.PageResult;
import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.common.UserContext;
import com.rj.exception.BusinessException;
import com.rj.mapper.WorkOrderMapper;
import com.rj.model.dto.ReviewDTO;
import com.rj.model.dto.ToolWorkOrderActionDTO;
import com.rj.model.enums.WorkOrderStatus;
import com.rj.model.pojo.WorkOrder;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IWorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tool 写服务的权限门与分页钳制测试（P1）。
 *
 * <p>为什么不启动 Spring：
 * 这层逻辑只依赖 {@link IWorkOrderService} 与 {@link WorkOrderMapper}，用 Mockito 就能验证
 * “先判权、后查库”的顺序。真正的事务/状态机/日志由集成测试覆盖。
 *
 * <p>ThreadLocal 纪律：{@link UserContext} 是线程绑定的，每个用例结束必须清理，
 * 否则同一个测试线程上的下一个用例会带着上一个用户的权限跑。
 */
class ToolWorkOrderServiceTest {

    private IWorkOrderService workOrderService;
    private WorkOrderMapper workOrderMapper;
    private ToolWorkOrderService toolService;

    @BeforeEach
    void setUp() {
        workOrderService = mock(IWorkOrderService.class);
        workOrderMapper = mock(WorkOrderMapper.class);
        toolService = new ToolWorkOrderService(workOrderService, workOrderMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listShouldClampCurrentAndSize() {
        // size=999 必须被静默钳到 50；current=0 必须被抬到 1
        when(workOrderService.page(1L, 50L, null, null, null))
                .thenReturn(new PageResult<>(List.of(), 0L, 1L, 50L));

        PageResult<WorkOrderVO> result =
                toolService.list(0L, 999L, null, null, null);

        assertThat(result.getSize()).isEqualTo(50L);
        verify(workOrderService).page(1L, 50L, null, null, null);
    }

    @Test
    void unknownActionShouldBeRejectedBeforeAnyDatabaseAccess() {
        loginWith("workorder:query");
        ToolWorkOrderActionDTO dto = dto("APPROVE", 0, true, null);

        assertThatThrownBy(() -> toolService.changeStatus(1L, dto))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());

        verifyNoInteractions(workOrderMapper, workOrderService);
    }

    @Test
    void missingPermissionShouldBeRejectedBeforeAnyDatabaseAccess() {
        // 只有查询权限，没有审核权限
        loginWith("workorder:query");
        ToolWorkOrderActionDTO dto = dto("REVIEW", 0, true, null);

        assertThatThrownBy(() -> toolService.changeStatus(1L, dto))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.FORBIDDEN.getCode());

        // 关键：无权限者不能通过 404/409/403 的差异探测工单是否存在
        verifyNoInteractions(workOrderMapper, workOrderService);
    }

    @Test
    void permittedReviewShouldDelegateToExistingService() {
        loginWith("workorder:review");
        ToolWorkOrderActionDTO dto = dto("REVIEW", 0, true, null);

        WorkOrder order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo("WO20261028000001");
        order.setStatus(WorkOrderStatus.PENDING_REVIEW.getCode());
        when(workOrderMapper.selectById(1L)).thenReturn(order);
        doNothing().when(workOrderService).review(eq(1L), any(ReviewDTO.class));

        ToolWorkOrderStatusVO result = toolService.changeStatus(1L, dto);

        assertThat(result.isChanged()).isTrue();
        assertThat(result.getFromStatus()).isEqualTo(0);
        assertThat(result.getTargetStatus()).isEqualTo(1);
        assertThat(result.getAction()).isEqualTo("REVIEW");
        verify(workOrderService).review(eq(1L), any(ReviewDTO.class));
    }

    @Test
    void expectedStatusMismatchShouldBeRejectedBeforeDelegation() {
        loginWith("workorder:review");
        // 预览时是 0，但库里实际已变成 1：意图已失效，不能重新解释成别的动作
        ToolWorkOrderActionDTO dto = dto("REVIEW", 0, true, null);

        WorkOrder order = new WorkOrder();
        order.setId(1L);
        order.setStatus(WorkOrderStatus.PENDING_DISPATCH.getCode());
        when(workOrderMapper.selectById(1L)).thenReturn(order);

        assertThatThrownBy(() -> toolService.changeStatus(1L, dto))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.CONFLICT.getCode());

        verify(workOrderMapper).selectById(1L);
        verifyNoInteractions(workOrderService);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    /** 设置当前线程的登录用户；roles 这里不参与 Tool 写判权，传空集合即可。 */
    private static void loginWith(String... permCodes) {
        UserContext.set(new UserAuth(1L, 10L, Set.of(), Set.of(permCodes)));
    }

    private static ToolWorkOrderActionDTO dto(String action, Integer expectedFrom,
                                              Boolean approved, Long handlerId) {
        ToolWorkOrderActionDTO dto = new ToolWorkOrderActionDTO();
        dto.setAction(action);
        dto.setExpectedFromStatus(expectedFrom);
        dto.setApproved(approved);
        dto.setHandlerId(handlerId);
        dto.setRemark("单元测试");
        return dto;
    }
}
