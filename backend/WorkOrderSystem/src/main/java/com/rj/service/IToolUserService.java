package com.rj.service;

import com.rj.model.vo.UserBriefVO;

import java.util.List;

/**
 * Agent Tool 接口的用户服务(项目二的调用入口)。
 * <p>
 * 目前只有一个能力:为派单/转派提供处理人候选。Agent 不能凭空猜 {@code handlerId},
 * 也不该让用户手输数字 ID,因此需要一个「按部门、按角色、按状态」裁剪过的只读列表。
 */
public interface IToolUserService {

    /**
     * 查询可指派处理人:仅启用账号、角色含 HANDLER。
     * <p>
     * 非 ADMIN 强制限定为自己的部门并忽略请求参数;ADMIN 可传部门过滤,
     * 不传则返回全部部门。调用者需具备派单或转派权限,否则 403。
     *
     * @param departmentId 可选,仅 ADMIN 生效
     * @param keyword      可选,模糊匹配账号或真实姓名;空表示不筛
     * @return 候选处理人(不含权限码)
     */
    List<UserBriefVO> listHandlers(Long departmentId, String keyword);
}
