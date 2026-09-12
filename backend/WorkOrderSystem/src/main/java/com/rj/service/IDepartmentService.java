package com.rj.service;

import com.rj.model.dto.DepartmentDTO;
import com.rj.model.pojo.Department;
import com.rj.model.vo.DepartmentBriefVO;

import java.util.List;

/**
 * 部门操作接口
 */
public interface IDepartmentService {

    /** 全部部门(下拉选项用) */
    List<Department> listAll();

    /**
     * 部门只读目录(id / 编码 / 名称)
     * <p>
     * 与 {@link #listAll} 的区别是仅需登录即可调用:工单里只有 departmentId,
     * 任何看过工单的角色都需要把裸ID翻译成部门名,而 listAll 要求 user:manage。
     * 返回精简过的 {@link DepartmentBriefVO},不含 remark 等内部信息。
     *
     * @return 部门目录,按 id 升序;无部门时返回空列表
     */
    List<DepartmentBriefVO> listDirectory();

    /** 新增部门,deptCode 需唯一 */
    void add(DepartmentDTO departmentDTO);

    /** 修改部门,deptCode 需唯一(排除自身) */
    void update(Long id, DepartmentDTO departmentDTO);

    /** 删除部门,存在用户或工单引用时拒绝 */
    void delete(Long id);
}
