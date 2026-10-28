package com.rj.controller.tool;

import com.rj.exception.ToolExceptionHandler;
import com.rj.model.vo.UserBriefVO;
import com.rj.model.vo.UserVO;
import com.rj.service.IToolUserService;
import com.rj.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `/tool/user/**` 的 HTTP 契约测试（P0 的配套）。
 *
 * <p>Agent 的工具可见性完全依赖 {@code /tool/user/info} 返回的扁平 {@code perms}。
 * 这里锁住两件事：
 * 1. 成功体不套 {@code Result}；
 * 2. 权限字段名必须是 {@code perms}，不能改成 {@code permissions}。
 */
class ToolUserControllerContractTest {

    private IUserService userService;
    private IToolUserService toolUserService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        toolUserService = mock(IToolUserService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ToolUserController(userService, toolUserService))
                .setControllerAdvice(new ToolExceptionHandler())
                .build();
    }

    @Test
    void infoShouldReturnBareUserWithPerms() throws Exception {
        UserVO user = new UserVO();
        user.setUserId(1L);
        user.setUsername("admin");
        user.setRoles(List.of("ADMIN"));
        user.setPerms(List.of("workorder:query", "workorder:review", "user:manage"));
        when(userService.me()).thenReturn(user);

        mockMvc.perform(get("/tool/user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.perms").isArray())
                .andExpect(jsonPath("$.perms[0]").value("workorder:query"))
                // 字段名是 perms，不是 permissions
                .andExpect(jsonPath("$.permissions").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void handlersShouldReturnBareArray() throws Exception {
        UserBriefVO handler = new UserBriefVO();
        handler.setUserId(5L);
        handler.setUsername("handler01");
        handler.setRealName("处理人小李");
        handler.setStatus(1);
        handler.setRoles(List.of("HANDLER"));
        when(toolUserService.listHandlers(1L, "李")).thenReturn(List.of(handler));

        mockMvc.perform(get("/tool/user/handlers")
                        .param("departmentId", "1")
                        .param("keyword", "李"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].userId").value(5))
                .andExpect(jsonPath("$[0].realName").value("处理人小李"))
                .andExpect(jsonPath("$[0].perms").doesNotExist());
    }
}
