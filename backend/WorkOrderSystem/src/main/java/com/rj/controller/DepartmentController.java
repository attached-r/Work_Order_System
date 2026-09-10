package com.rj.controller;

import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.pojo.Department;
import com.rj.service.IDepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
