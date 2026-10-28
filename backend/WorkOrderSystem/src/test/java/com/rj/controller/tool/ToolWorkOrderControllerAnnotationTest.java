package com.rj.controller.tool;

import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.ToolWorkOrderActionDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller 权限注解回归测试（P1）。
 *
 * <p>这不是业务测试，而是防止以后“顺手加个注解”破坏 M4 的设计：
 * <ul>
 *   <li>列表 / 详情：需要 {@code workorder:query}；</li>
 *   <li>写接口：<b>刻意不挂注解</b>，因为六个动作的权限码不同，
 *       单条注解的 AND 语义无法表达“六选一”，判权在 Tool Service 内按动作做。</li>
 * </ul>
 */
class ToolWorkOrderControllerAnnotationTest {

    @Test
    void listAndDetailShouldRequireWorkorderQuery() throws Exception {
        Method list = ToolWorkOrderController.class.getMethod(
                "list", long.class, long.class, Integer.class, Integer.class, String.class);
        Method detail = ToolWorkOrderController.class.getMethod("detail", Long.class);

        RequiresPermission listPermission = list.getAnnotation(RequiresPermission.class);
        RequiresPermission detailPermission = detail.getAnnotation(RequiresPermission.class);

        assertThat(listPermission).isNotNull();
        assertThat(listPermission.value()).containsExactly("workorder:query");

        assertThat(detailPermission).isNotNull();
        assertThat(detailPermission.value()).containsExactly("workorder:query");
    }

    @Test
    void changeStatusShouldNotHavePermissionAnnotation() throws Exception {
        Method changeStatus = ToolWorkOrderController.class.getMethod(
                "changeStatus", Long.class, ToolWorkOrderActionDTO.class);

        // 这是有意的：写接口在 Service 内按 action 逐个判权，不能被一条 AND 注解误伤
        assertThat(changeStatus.getAnnotation(RequiresPermission.class)).isNull();
    }
}
