package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.common.UserContext;
import com.rj.exception.BusinessException;
import com.rj.mapper.DepartmentMapper;
import com.rj.mapper.RoleMapper;
import com.rj.mapper.UserMapper;
import com.rj.mapper.UserRoleMapper;
import com.rj.model.dto.RegisterDTO;
import com.rj.model.pojo.Department;
import com.rj.model.pojo.Role;
import com.rj.model.pojo.User;
import com.rj.model.pojo.UserRole;
import com.rj.model.vo.UserVO;
import com.rj.service.IAuthService;
import com.rj.service.IUserService;
import com.rj.util.JwtUtil;
import com.rj.util.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.rj.common.RedisConstants.LOGIN_TOKEN_EXPIRE_HOURS;
import static com.rj.common.RedisConstants.LOGIN_USER_TOKEN_KEY;

/**
 * 用户操作方法实现
 * */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements  IUserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final DepartmentMapper departmentMapper;
    private final PasswordUtil passwordUtil;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final IAuthService authService;

    /**
     *  登录
     *  通过唯一比较username 和 password
     * */
    @Override
    public Result<String> login(String username, String password) {

        // 登录名唯一 按username 查询用用户记录
        User user = userMapper.selectOne(  //通过userMapper快速构建查询语句
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );

        // 登录失败统一提示，避免泄露账号是否存在
        if (user == null || !passwordUtil.matches(password, user.getPassword())) {
            if (user != null) {
                log.warn("用户登录失败: username={}",user.getUsername());
            }
            throw new BusinessException(ResultCode.UNAUTHORIZED,"账户或密码错误");
        }

        // 账号状态:0=禁用 1=启用,禁用账号不允许登录
        if (user.getStatus() == 0) {
            log.warn("该账号已停用,禁止登录: userId={},username={}", user.getId(), user.getUsername());
            throw new BusinessException(ResultCode.FORBIDDEN, "该账号已停用");
        }

        // 确认用户存在且合法 设置token
        String token = jwtUtil.createToken(user.getId());

        // 一个用户只保留一个 有效token;重复登录会覆盖旧的token
        stringRedisTemplate.opsForValue().set(
                LOGIN_USER_TOKEN_KEY + user.getId(),
                token,
                LOGIN_TOKEN_EXPIRE_HOURS, TimeUnit.HOURS
        );

        // 组装鉴权快照(部门+角色+权限)并缓存,后续请求直接读缓存做权限校验
        authService.reload(user.getId());

        log.info("登录成功: userId={}, userName={}",user.getId(),user.getUsername());
        return Result.success(token);

    }

    /**
     * 注册
     * <p>
     * 只创建账号并落库,不分配角色——新账号须由管理员通过
     * PUT /user/{userId}/roles 赋权后才能访问受限接口。
     *
     * @param registerDTO 注册信息,密码与确认密码需一致
     * @return 成功结果(不携带数据),由前端引导用户去登录
     * @throws BusinessException 两次密码不一致(400)、账号已存在(409)
     */
    @Override
    public Result<Void> register(RegisterDTO registerDTO) {
        // 两次输入密码一致性校验
        if (!registerDTO.getPassword().equals(registerDTO.getConfirmPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两次输入的密码不一致");
        }

        // 账号唯一性校验(username 有唯一索引,selectCount 会自动过滤逻辑删除的账号)
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, registerDTO.getUsername())
        );
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "账号已存在,请更换账号");
        }

        // 组装并落库:密码 BCrypt 加密,status 默认启用(1)
        // 角色不在此处分配,由管理员后续通过 /user/{userId}/roles 赋予
        User user = User.builder()
                .username(registerDTO.getUsername())
                .password(passwordUtil.encode(registerDTO.getPassword()))
                .realName(registerDTO.getRealName())
                .phone(registerDTO.getPhone())
                .departmentId(registerDTO.getDepartmentId())
                .status(1)
                .build();
        userMapper.insert(user);

        log.info("注册成功: userId={}, username={}", user.getId(), user.getUsername());
        return Result.success();
    }

    /**
     * 当前登录用户信息
     * <p>
     * 用户ID取自 {@link UserContext}(由登录拦截器写入);角色与权限直接读
     * 上下文里的鉴权快照,不再查库。前端用这份数据控制菜单/按钮显隐。
     *
     * @return 用户基本信息 + 部门名称 + 角色码 + 权限码
     * @throws BusinessException 用户已被删除时抛 401,要求重新登录
     */
    @Override
    public UserVO me() {

        // 从UserContext中读取UserId并做查询
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效,请重新登录");
        }

        // 组装 USerVO
        // 1. 封装基础信息 + 部门名称
        UserVO vo = baseVo(user);
        vo.setDepartmentName(loadDepartmentName(user.getDepartmentId()));

        // 2. 封装角色和权限
        UserAuth userAuth = UserContext.get();
        if (userAuth != null) {
            vo.setRoles(new ArrayList<>(userAuth.getRoleCodes()));
            vo.setPerms(new ArrayList<>(userAuth.getPermCodes()));
        }
        return vo;
    }

    /**
     * 用户分页列表
     * <p>
     * keyword 同时模糊匹配账号与真实姓名;部门名与角色走"批量一次查回 + 内存装配",
     * 而不是逐行查库,避免分页条数越多查询次数越多的 N+1 问题。
     *
     * @param current 页码,从 1 开始
     * @param size    每页条数
     * @param keyword 可选关键字,为空则不过滤
     * @return 分页结果(含总条数、当前页、每页条数)
     */
    @Override
    public PageResult<UserVO> pageUsers(long current, long size, String keyword) {
        // 组装查询 wrapper,按 id 升序保证分页顺序稳定(否则 MySQL 翻页可能重复或漏行)
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().orderByAsc(User::getId);
        if (StringUtils.hasText(keyword)) {
            // 账号或姓名任一命中即可;用 and(w -> ...) 把 OR 包成一组,
            // 避免 OR 与逻辑删除等其它条件平级导致条件被"或"掉
            wrapper.and(w -> w.like(User::getUsername, keyword).or().like(User::getRealName, keyword));
        }
        // 执行分页查询:总数 + 当前页数据(COUNT 与 LIMIT 由分页插件自动拼)
        Page<User> page = userMapper.selectPage(new Page<>(current, size), wrapper);

        List<User> users = page.getRecords();
        // 空页直接返回,省掉下面的批量装配
        if (users.isEmpty()) {
            return new PageResult<>(List.<UserVO>of(), page.getTotal(), page.getCurrent(), page.getSize());
        }

        // 批量装配部门名与角色,避免逐行查库
        List<Long> userIds = users.stream().map(User::getId).toList();
        // 收集本页涉及的部门ID:过滤 null(ADMIN 等全局账号可能无部门)并去重,缩小 IN 规模
        List<Long> deptIds = users.stream().map(User::getDepartmentId)
                .filter(Objects::nonNull).distinct().toList();
        // 一次 IN 查回 id -> 部门名;没有部门则跳过查询,用空表兜底
        Map<Long, String> deptNameMap = deptIds.isEmpty() ? Map.<Long, String>of()
                : departmentMapper.selectList(new LambdaQueryWrapper<Department>().in(Department::getId, deptIds))
                        .stream().collect(Collectors.toMap(Department::getId, Department::getDeptName));
        Map<Long, List<String>> userRoleMap = loadUserRoleCodes(userIds);

        // 内存装配:部门名按 id 取,角色取不到(该用户无角色)兜底成空列表
        List<UserVO> records = users.stream().map(user -> {
            UserVO vo = baseVo(user);
            vo.setDepartmentName(user.getDepartmentId() == null ? null : deptNameMap.get(user.getDepartmentId()));
            vo.setRoles(userRoleMap.getOrDefault(user.getId(), List.<String>of()));
            return vo;
        }).toList();

        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 覆盖式分配用户角色
     * <p>
     * 先校验用户与角色ID均有效,再"清空旧关联 + 写入新关联",整个过程在一个事务里
     * (任一步失败即回滚,避免清空成功、写入失败导致用户丢角色)。
     * 最后驱逐鉴权缓存,使新角色在下一次请求立即生效。
     *
     * @param userId  目标用户ID
     * @param roleIds 新的角色ID集合,重复值会被去重
     * @throws BusinessException 用户不存在(404)、存在无效角色ID(400)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);

        // 先去重:重复的 roleId 会让"查到的个数"与"传入个数"对不上,被误判成无效ID
        List<Long> distinctRoleIds = roleIds.stream().distinct().toList();
        // 用 count 反查这些角色ID是否都真实存在:个数不符说明混进了不存在的ID
        Long validCount = roleMapper.selectCount(
                new LambdaQueryWrapper<Role>().in(Role::getId, distinctRoleIds));
        if (validCount == null || validCount != distinctRoleIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "存在无效的角色ID");
        }

        // 覆盖式:先清空旧关联,再写入新关联
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        // 逐条插入新关联;整段在一个事务里,插入中途失败会连上面的删除一起回滚
        for (Long roleId : distinctRoleIds) {
            userRoleMapper.insert(UserRole.builder().userId(userId).roleId(roleId).build());
        }

        // 角色已变,删除鉴权缓存 -> 下次请求回源重建,立即生效
        authService.evict(userId);
        log.info("分配角色: userId={}, roleIds={}", userId, distinctRoleIds);
    }

    /**
     * 调整用户所属部门
     * <p>
     * 校验用户与部门均存在后更新 department_id,并驱逐鉴权缓存使数据范围立即生效。
     * 仅传 id 与 departmentId,其余字段为 null 会被 MyBatis-Plus 默认更新策略跳过。
     *
     * @param userId       目标用户ID
     * @param departmentId 新部门ID
     * @throws BusinessException 用户不存在(404)、部门不存在(400)
     */
    @Override
    public void assignDepartment(Long userId, Long departmentId) {
        requireUser(userId);

        // 部门不存在直接拒绝,避免写入指向空部门的 department_id
        if (departmentMapper.selectById(departmentId) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "部门不存在");
        }

        // 只更新 department_id,其余字段为 null 会被 MyBatis-Plus 的默认策略跳过
        userMapper.updateById(User.builder().id(userId).departmentId(departmentId).build());

        // 部门变了 -> 数据范围随之改变,驱逐缓存让下次请求重建
        authService.evict(userId);
        log.info("调整部门: userId={}, departmentId={}", userId, departmentId);
    }

    /** 组装 UserVO 的公共字段 */
    private UserVO baseVo(User user) {
        UserVO vo = new UserVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setStatus(user.getStatus());
        return vo;
    }

    /** 通过部门id 获取部门名称*/
    private String loadDepartmentName(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentMapper.selectById(departmentId);
        return department == null ? null : department.getDeptName();
    }

    /**
     * 批量查询 userId -> 角色码列表
     * <p>
     * 固定 2 次查询完成整页装配:先查关联表,再查角色码表,最后在内存里分组,
     * 查询次数与用户数无关,避免逐个用户查角色的 N+1。
     *
     * @param userIds 当前页的用户ID
     * @return userId -> 角色码列表;没有任何角色的用户不会出现在返回的 Map 中
     */
    private Map<Long, List<String>> loadUserRoleCodes(List<Long> userIds) {
        // 第一步:一次取回这页用户的所有"用户-角色"关联行
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, userIds));
        // 一个关联都没有,直接返回空表,省掉后面两步查询
        if (userRoles.isEmpty()) {
            return Map.of();
        }

        // 抽出去重后的角色ID:多个用户常共享同一角色,去重可缩小第二次 IN 的规模
        Set<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toSet());
        // 第二步:一次查回 roleId -> 角色码,之后全在内存里拼装,不再回库
        Map<Long, String> roleCodeMap = roleMapper.selectList(
                        new LambdaQueryWrapper<Role>().in(Role::getId, roleIds))
                .stream().collect(Collectors.toMap(Role::getId, Role::getRoleCode));

        // 按 userId 分组,把关联行翻译成角色码列表
        return userRoles.stream()
                // 防脏数据:关联表里的 roleId 若在 role 表已不存在则取到 null,过滤掉以免 null 混入结果
                .filter(userRole -> roleCodeMap.containsKey(userRole.getRoleId()))
                .collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(userRole -> roleCodeMap.get(userRole.getRoleId()), Collectors.toList())));
    }

    /** 校验用户存在,不存在即抛 404,供各写操作复用 */
    private void requireUser(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
    }

}
