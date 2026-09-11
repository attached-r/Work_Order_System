package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限返回视图(树节点)
 * <p>
 * 在 {@link com.rj.model.pojo.Permission} 基础上补一个 children 字段,
 * 由 Service 层在内存里按 parentId 装配成树,供前端权限分配控件直接渲染。
 */
@Data
@Schema(description = "权限树节点")
public class PermissionVO {

    @Schema(description = "权限ID")
    private Long id;

    @Schema(description = "权限标识:如 workorder:review")
    private String permCode;

    @Schema(description = "权限名称:如 审核工单")
    private String permName;

    @Schema(description = "父权限ID,一级权限为 null")
    private Long parentId;

    @Schema(description = "子权限,无子权限时为空列表")
    private List<PermissionVO> children = new ArrayList<>();
}
