package com.rj.controller.tool;

import com.rj.common.PageResult;
import com.rj.common.ResultCode;
import com.rj.exception.BusinessException;
import com.rj.exception.ToolExceptionHandler;
import com.rj.model.dto.ToolWorkOrderActionDTO;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IToolWorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `/tool/workorder/**` 的 HTTP 契约测试（P0）。
 *
 * <p>为什么不用完整 {@code @SpringBootTest}：
 * 这里要锁的是“路径 / 参数 / JSON 形状 / 错误体”，不是数据库或 JWT。
 * 用 standalone MockMvc + mock Service，测试快、失败点清晰。
 *
 * <p>契约有三个硬约定，测试必须逐条守住：
 * 1. 成功体是**裸数据**，不套 `{code,data}`；
 * 2. 业务错误体是 `{code,message}`，且 **HTTP 状态仍是 200**；
 * 3. `@Valid` 校验失败也走 `ToolExceptionHandler`，不能被兜底成 500。
 */
class ToolWorkOrderControllerContractTest {

    private IToolWorkOrderService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(IToolWorkOrderService.class);

        // standalone MockMvc 不会自动装配 Bean Validation，需要手动给一个 validator，
        // 否则 @Valid 不生效，缺字段的请求会被直接交给 Service。
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ToolWorkOrderController(service))
                .setControllerAdvice(new ToolExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listShouldReturnBarePageResult() throws Exception {
        WorkOrderVO vo = new WorkOrderVO();
        vo.setId(1L);
        vo.setTitle("打印机故障");
        vo.setStatus(0);
        when(service.list(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(vo), 1L, 1L, 10L));

        mockMvc.perform(get("/tool/workorder/list")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                // 裸 PageResult：顶层有 records，顶层没有 code
                .andExpect(jsonPath("$.records").isArray())
                .andExpect(jsonPath("$.records[0].title").value("打印机故障"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void detailShouldReturnBareDetail() throws Exception {
        WorkOrderDetailVO detail = new WorkOrderDetailVO();
        detail.setId(7L);
        detail.setTitle("会议室投影仪故障");
        when(service.detail(7L)).thenReturn(detail);

        mockMvc.perform(get("/tool/workorder/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.title").value("会议室投影仪故障"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void changeStatusShouldReturnBareActionResult() throws Exception {
        ToolWorkOrderStatusVO result = new ToolWorkOrderStatusVO();
        result.setId(1L);
        result.setOrderNo("WO20261028000001");
        result.setAction("REVIEW");
        result.setFromStatus(0);
        result.setTargetStatus(1);
        result.setChanged(true);
        when(service.changeStatus(eq(1L), any(ToolWorkOrderActionDTO.class)))
                .thenReturn(result);

        mockMvc.perform(put("/tool/workorder/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REVIEW","expectedFromStatus":0,"approved":true,"remark":"同意"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.fromStatus").value(0))
                .andExpect(jsonPath("$.targetStatus").value(1))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void missingRequiredFieldsShouldReturnToolErrorBodyWithHttp200() throws Exception {
        // @Valid 失败：HTTP 仍是 200，真实错误在 body 的 code
        mockMvc.perform(put("/tool/workorder/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void outOfRangeExpectedStatusShouldBeRejectedByValidation() throws Exception {
        mockMvc.perform(put("/tool/workorder/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REVIEW","expectedFromStatus":9,"approved":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    void forbiddenBusinessErrorShouldKeepHttp200AndSimplifiedBody() throws Exception {
        when(service.changeStatus(eq(1L), any(ToolWorkOrderActionDTO.class)))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN, "缺少操作权限: workorder:review"));

        mockMvc.perform(put("/tool/workorder/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REVIEW","expectedFromStatus":0,"approved":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()))
                .andExpect(jsonPath("$.message").value("缺少操作权限: workorder:review"))
                // 精简错误体：不套 Result，不出现 data
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void conflictBusinessErrorShouldKeepHttp200AndSimplifiedBody() throws Exception {
        when(service.changeStatus(eq(1L), any(ToolWorkOrderActionDTO.class)))
                .thenThrow(new BusinessException(ResultCode.CONFLICT,
                        "工单状态已变更，请重新查询确认后再操作"));

        mockMvc.perform(put("/tool/workorder/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REVIEW","expectedFromStatus":0,"approved":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CONFLICT.getCode()))
                .andExpect(jsonPath("$.message").value("工单状态已变更，请重新查询确认后再操作"));
    }
}
