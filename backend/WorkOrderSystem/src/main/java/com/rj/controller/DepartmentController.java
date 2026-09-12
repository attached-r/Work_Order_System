package com.rj.controller;

import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.DepartmentDTO;
import com.rj.model.pojo.Department;
import com.rj.model.vo.DepartmentBriefVO;
import com.rj.service.IDepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "部门管理")
@RequestMapping("/department")
@RestController
@RequiredArgsConstructor
public class DepartmentController {

    private final IDepartmentService departmentService;

    /**
     * 部门下拉选项,分配部门时用。
     */
    @Operation(summary = "部门列表")
    @RequiresPermission("user:manage")
    @GetMapping("/list")
    public Result<List<Department>> list() {
        return Result.success(departmentService.listAll());
    }

    /**
     * 部门只读目录,把工单里的 departmentId 解析成部门名。
     * <p>
     * <b>刻意不挂 {@code @RequiresPermission}:</b>能看工单的角色未必有 {@code user:manage},
     * 不加注解即"登录即可"(拦截器仍校验 token)。返回不含 remark 的精简字段。
     */
    @Operation(summary = "部门目录")
    @GetMapping("/directory")
    public Result<List<DepartmentBriefVO>> directory() {
        return Result.success(departmentService.listDirectory());
    }

    /**
     * 新增部门,deptCode 唯一。
     */
    @Operation(summary = "新增部门")
    @RequiresPermission("user:manage")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody DepartmentDTO departmentDTO) {
        departmentService.add(departmentDTO);
        return Result.success();
    }

    /**
     * 修改部门,deptCode 唯一(排除自身)。
     */
    @Operation(summary = "修改部门")
    @RequiresPermission("user:manage")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DepartmentDTO departmentDTO) {
        departmentService.update(id, departmentDTO);
        return Result.success();
    }

    /**
     * 删除部门,仍被用户或工单引用时拒绝。
     */
    @Operation(summary = "删除部门")
    @RequiresPermission("user:manage")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return Result.success();
    }
}
