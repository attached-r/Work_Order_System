package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部门只读目录项
 * <p>
 * 即 {@code Department} 去掉 {@code remark} / {@code createTime}:目录只用于把工单里的
 * {@code departmentId} 翻译成部门名,备注属于内部信息,没必要对所有登录用户暴露。
 * 与 {@link UserBriefVO} 一样,该接口仅需登录,不要求 {@code user:manage}。
 */
@Data
@Schema(description = "部门只读目录项")
public class DepartmentBriefVO {

    @Schema(description = "部门id")
    private Long id;

    @Schema(description = "部门编码")
    private String deptCode;

    @Schema(description = "部门名称")
    private String deptName;
}
