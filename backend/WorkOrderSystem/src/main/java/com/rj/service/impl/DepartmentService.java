package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rj.common.ResultCode;
import com.rj.exception.BusinessException;
import com.rj.mapper.DepartmentMapper;
import com.rj.mapper.UserMapper;
import com.rj.mapper.WorkOrderMapper;
import com.rj.model.dto.DepartmentDTO;
import com.rj.model.pojo.Department;
import com.rj.model.pojo.User;
import com.rj.model.pojo.WorkOrder;
import com.rj.service.IDepartmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部门操作实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService implements IDepartmentService {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final WorkOrderMapper workOrderMapper;

    /**
     * 全部部门,按 id 升序,供前端下拉选择。
     *
     * @return 部门列表
     */
    @Override
    public List<Department> listAll() {
        // 部门是字典数据、条数很少,直接全查;按 id 升序保证下拉顺序稳定
        return departmentMapper.selectList(new LambdaQueryWrapper<Department>().orderByAsc(Department::getId));
    }

    /**
     * 新增部门
     * <p>
     * deptCode 是唯一业务标识(uk_dept_code),插入前先查重给出明确提示,
     * 避免直接撞唯一索引抛数据库异常。
     *
     * @param departmentDTO 部门信息
     * @throws BusinessException 部门编码已存在(409)
     */
    @Override
    public void add(DepartmentDTO departmentDTO) {
        requireDeptCodeAvailable(departmentDTO.getDeptCode(), null);

        // create_time 不设值,交由数据库默认值 CURRENT_TIMESTAMP 填充
        Department department = Department.builder()
                .deptCode(departmentDTO.getDeptCode())
                .deptName(departmentDTO.getDeptName())
                .remark(departmentDTO.getRemark())
                .build();
        departmentMapper.insert(department);

        log.info("新增部门: id={}, deptCode={}", department.getId(), department.getDeptCode());
    }

    /**
     * 修改部门
     *
     * @param id            目标部门ID
     * @param departmentDTO 部门信息
     * @throws BusinessException 部门不存在(404)、部门编码已被其它部门占用(409)
     */
    @Override
    public void update(Long id, DepartmentDTO departmentDTO) {
        requireDepartment(id);
        // 排除自身后再查重,否则改成原编码会被误判成冲突
        requireDeptCodeAvailable(departmentDTO.getDeptCode(), id);

        // 只更新业务字段;id 用于定位,create_time 不参与修改
        departmentMapper.updateById(Department.builder()
                .id(id)
                .deptCode(departmentDTO.getDeptCode())
                .deptName(departmentDTO.getDeptName())
                .remark(departmentDTO.getRemark())
                .build());

        log.info("修改部门: id={}, deptCode={}", id, departmentDTO.getDeptCode());
    }

    /**
     * 删除部门
     * <p>
     * department 表无逻辑删除列,此处为物理删除;删除前校验是否被用户或工单引用,
     * 有引用则拒绝,避免留下指向空部门的 department_id。
     *
     * @param id 目标部门ID
     * @throws BusinessException 部门不存在(404)、仍被用户或工单引用(409)
     */
    @Override
    public void delete(Long id) {
        requireDepartment(id);

        // User/WorkOrder 均带 @TableLogic,selectCount 自动排除已逻辑删除的记录
        Long userCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getDepartmentId, id));
        if (userCount != null && userCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该部门下仍有用户,无法删除");
        }
        Long orderCount = workOrderMapper.selectCount(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getDepartmentId, id));
        if (orderCount != null && orderCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该部门下仍有工单,无法删除");
        }

        departmentMapper.deleteById(id);
        log.info("删除部门: id={}", id);
    }

    /** 校验部门存在,不存在即抛 404,供 update/delete 复用 */
    private void requireDepartment(Long id) {
        if (departmentMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }
    }

    /**
     * 校验部门编码未被占用
     *
     * @param deptCode  待校验的部门编码
     * @param excludeId 修改时传入自身ID以排除自身;新增传 null
     */
    private void requireDeptCodeAvailable(String deptCode, Long excludeId) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<Department>()
                .eq(Department::getDeptCode, deptCode);
        if (excludeId != null) {
            wrapper.ne(Department::getId, excludeId);
        }
        Long count = departmentMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "部门编码已存在");
        }
    }
}
