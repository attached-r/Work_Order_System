package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rj.common.PageResult;
import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.common.UserContext;
import com.rj.exception.BusinessException;
import com.rj.mapper.UserMapper;
import com.rj.mapper.WorkOrderMapper;
import com.rj.mapper.WorkOrderOperateLogMapper;
import com.rj.mapper.WorkOrderResourceMapper;
import com.rj.model.dto.AcceptDTO;
import com.rj.model.dto.DispatchDTO;
import com.rj.model.dto.ProcessDTO;
import com.rj.model.dto.ReviewDTO;
import com.rj.model.dto.WithdrawDTO;
import com.rj.model.dto.WorkOrderCreateDTO;
import com.rj.model.dto.WorkOrderResourceDTO;
import com.rj.model.enums.OperateType;
import com.rj.model.enums.WorkOrderStatus;
import com.rj.model.pojo.WorkOrder;
import com.rj.model.pojo.WorkOrderOperateLog;
import com.rj.model.pojo.WorkOrderResource;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderStatsVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IWorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 工单业务实现(模块二)。
 * <p>
 * 核心设计:
 * <ul>
 *   <li><b>状态机集中校验</b>:所有状态变更都走 {@link #transition} ,由
 *       {@link WorkOrderStatus#canTransitionTo} 判定合法性,非法迁移统一抛 409。</li>
 *   <li><b>乐观锁防并发</b>:主表带 {@code @Version},并发修改只有一方成功,
 *       {@code updateById} 影响行数为 0 即视为版本冲突。</li>
 *   <li><b>数据范围隔离</b>:列表与详情按角色(提单人/本部门审核派单人/处理人/管理员)
 *       自动收敛可见范围,动作接口再叠加「谁可操作」校验。</li>
 *   <li><b>操作留痕</b>:每次流转都在 work_order_operate_log 落一行,记录前后状态。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService implements IWorkOrderService {

    /** 角色码(与 role.role_code 对应) */
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_SUBMITTER = "SUBMITTER";
    private static final String ROLE_REVIEWER = "REVIEWER";
    private static final String ROLE_DISPATCHER = "DISPATCHER";
    private static final String ROLE_HANDLER = "HANDLER";

    /** 权限码 */
    private static final String PERM_DISPATCH = "workorder:dispatch";
    private static final String PERM_TRANSFER = "workorder:transfer";

    /** 未指定超时时间时的默认时长(小时) */
    private static final long DEFAULT_EXPIRE_HOURS = 72L;

    /** 超时扫描单轮批量上限,避免一次拉取过多拖垮数据库 */
    private static final int TIMEOUT_BATCH_SIZE = 200;

    /** 系统操作人ID:超时关闭由定时任务触发,无真实登录用户,记为 0 */
    private static final long SYSTEM_OPERATOR_ID = 0L;

    /** 工单编号时间部分格式:精确到毫秒 */
    private static final DateTimeFormatter ORDER_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderResourceMapper workOrderResourceMapper;
    private final WorkOrderOperateLogMapper workOrderOperateLogMapper;
    private final UserMapper userMapper;

    /**
     * 创建工单:主表与资源明细在同一事务写入,任一失败整体回滚。
     * 提单人、归属部门取自登录上下文,不接受前端传入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(WorkOrderCreateDTO dto) {
        Long userId = UserContext.getUserId();
        Long departmentId = UserContext.getDepartmentId();
        // 部门是工单数据隔离的基础,无部门的账号(如全局 ADMIN)不允许提单
        if (departmentId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前用户未归属部门,无法提交工单");
        }

        WorkOrder order = WorkOrder.builder()
                .orderNo(generateOrderNo())
                .userId(userId)
                .departmentId(departmentId)
                .title(dto.getTitle())
                .content(dto.getContent())
                .orderType(dto.getOrderType())
                .priority(dto.getPriority())
                .status(WorkOrderStatus.PENDING_REVIEW.getCode())
                .expireTime(resolveExpireTime(dto.getExpireTime()))
                .build();
        workOrderMapper.insert(order);

        // 资源明细随主表写入子表;明细本身不单独维护状态,跟随主表流转
        if (dto.getResources() != null) {
            for (WorkOrderResourceDTO item : dto.getResources()) {
                workOrderResourceMapper.insert(WorkOrderResource.builder()
                        .orderId(order.getId())
                        .resourceType(item.getResourceType())
                        .resourceName(item.getResourceName())
                        .quantity(item.getQuantity())
                        .unit(item.getUnit())
                        .remark(item.getRemark())
                        .build());
            }
        }

        // 首次提交:from_status 为空,to_status 为「待审核」
        insertLog(order.getId(), userId, OperateType.SUBMIT, null,
                WorkOrderStatus.PENDING_REVIEW.getCode(), null, "提交工单");
        log.info("创建工单: id={}, orderNo={}, userId={}", order.getId(), order.getOrderNo(), userId);
        return order.getId();
    }

    /**
     * 重新提交:被驳回的工单由提单人修改后重投,回到待审核。
     * <p>
     * 与创建的区别只在「需要先有一张已驳回(5)的工单」——状态合法性交给状态机
     * ({@code REJECTED → PENDING_REVIEW})判定,复用同一个 {@code transition} 出口。
     * 明细采用覆盖式替换语义(与角色/权限分配一致):传了就以本次为准,不传则保留原样。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmit(Long id, WorkOrderCreateDTO dto) {
        WorkOrder order = requireOrder(id);
        requireSubmitterSelf(order);

        // 先走状态流转(顺带校验 5→0 是否合法),再修改工单内容;整段在同一事务里
        transition(order, WorkOrderStatus.PENDING_REVIEW, OperateType.SUBMIT, null, null, updated -> {
            updated.setTitle(dto.getTitle());
            updated.setContent(dto.getContent());
            updated.setOrderType(dto.getOrderType());
            updated.setPriority(dto.getPriority());
            // 超时时间:重投时传了才重置,否则沿用原值(避免无条件再顺延)
            if (dto.getExpireTime() != null) {
                updated.setExpireTime(resolveExpireTime(dto.getExpireTime()));
            }
        });

        // 资源明细:传了就整体替换,不传则不动
        if (dto.getResources() != null) {
            workOrderResourceMapper.delete(new LambdaQueryWrapper<WorkOrderResource>()
                    .eq(WorkOrderResource::getOrderId, id));
            for (WorkOrderResourceDTO item : dto.getResources()) {
                workOrderResourceMapper.insert(WorkOrderResource.builder()
                        .orderId(id)
                        .resourceType(item.getResourceType())
                        .resourceName(item.getResourceName())
                        .quantity(item.getQuantity())
                        .unit(item.getUnit())
                        .remark(item.getRemark())
                        .build());
            }
        }
    }

    /**
     * 分页查询:按角色叠加数据范围——提单人看自己的、审核/派单人看本部门的、
     * 处理人看指派给自己的,管理员看全部(多种角色取并集)。
     */
    @Override
    public PageResult<WorkOrderVO> page(long current, long size, Integer status, Integer orderType, String keyword) {
        LambdaQueryWrapper<WorkOrder> wrapper = buildListWrapper(orderType, keyword);
        if (status != null) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        wrapper.orderByDesc(WorkOrder::getCreateTime);

        Page<WorkOrder> page = workOrderMapper.selectPage(new Page<>(current, size), wrapper);
        List<WorkOrderVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 工单统计:与列表查询共用同一套过滤与数据范围,只在最后一步按状态分组。
     * <p>
     * 这里只 select status 一列再在内存里分组,而不是拼 {@code GROUP BY} 的原生 SQL:
     * 数据范围条件是由 {@link #applyListScope} 动态拼出的嵌套 and/or 条件,换成原生
     * SQL 就要把那套逻辑重写一遍,两边一旦不同步就会「列表看到 10 条、统计说 8 条」。
     * 单列扫描的开销远小于这份一致性风险。
     */
    @Override
    public WorkOrderStatsVO stats(Integer orderType, String keyword) {
        LambdaQueryWrapper<WorkOrder> wrapper = buildListWrapper(orderType, keyword);
        wrapper.select(WorkOrder::getStatus);

        Map<Integer, Long> counted = workOrderMapper.selectList(wrapper).stream()
                .collect(Collectors.groupingBy(WorkOrder::getStatus, Collectors.counting()));

        // 8 个状态全返回(含 0),前端直接渲染,不必自己补缺
        List<WorkOrderStatsVO.StatusCount> statusCounts = Arrays.stream(WorkOrderStatus.values())
                .map(status -> new WorkOrderStatsVO.StatusCount(
                        status.getCode(),
                        status.getDesc(),
                        counted.getOrDefault(status.getCode(), 0L)))
                .toList();

        WorkOrderStatsVO vo = new WorkOrderStatsVO();
        vo.setStatusCounts(statusCounts);
        vo.setTotal(statusCounts.stream().mapToLong(WorkOrderStatsVO.StatusCount::getCount).sum());
        return vo;
    }

    /**
     * 构造列表/统计共用的查询条件:类型 + 关键词 + 数据范围。
     * 抽出这个方法是为了让「列表」与「统计」不可能出现口径差异。
     */
    private LambdaQueryWrapper<WorkOrder> buildListWrapper(Integer orderType, String keyword) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (orderType != null) {
            wrapper.eq(WorkOrder::getOrderType, orderType);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(WorkOrder::getTitle, keyword)
                    .or().like(WorkOrder::getOrderNo, keyword));
        }
        applyListScope(wrapper);
        return wrapper;
    }

    /**
     * 工单详情:主表 + 资源明细 + 操作日志,并做可见范围校验。
     */
    @Override
    public WorkOrderDetailVO detail(Long id) {
        WorkOrder order = requireOrder(id);
        requireViewScope(order);

        WorkOrderDetailVO vo = new WorkOrderDetailVO();
        BeanUtils.copyProperties(order, vo);
        vo.setStatusDesc(WorkOrderStatus.fromCode(order.getStatus()).getDesc());
        vo.setResources(workOrderResourceMapper.selectList(new LambdaQueryWrapper<WorkOrderResource>()
                .eq(WorkOrderResource::getOrderId, id)
                .orderByAsc(WorkOrderResource::getId)));
        vo.setLogs(workOrderOperateLogMapper.selectList(new LambdaQueryWrapper<WorkOrderOperateLog>()
                .eq(WorkOrderOperateLog::getOrderId, id)
                .orderByAsc(WorkOrderOperateLog::getCreateTime)));
        return vo;
    }

    /** 审核:本部门审核人操作,通过 → 待派单,驳回 → 已驳回 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(Long id, ReviewDTO dto) {
        WorkOrder order = requireOrder(id);
        requireDepartmentScope(order);
        boolean approved = Boolean.TRUE.equals(dto.getApproved());
        transition(order,
                approved ? WorkOrderStatus.PENDING_DISPATCH : WorkOrderStatus.REJECTED,
                approved ? OperateType.REVIEW_PASS : OperateType.REVIEW_REJECT,
                null, dto.getRemark());
    }

    /**
     * 派单 / 转派:入口不挂 {@code @RequiresPermission}(派单与转派所需权限不同,
     * 无法用「全部满足」的注解同时表达),改由本方法按当前状态做精确判权。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispatch(Long id, DispatchDTO dto) {
        WorkOrder order = requireOrder(id);
        requireHandlerExists(dto.getHandlerId());

        WorkOrderStatus current = WorkOrderStatus.fromCode(order.getStatus());
        if (current == WorkOrderStatus.PENDING_DISPATCH) {
            // 派单:需派单权限,且限于本部门工单
            requirePermission(PERM_DISPATCH);
            requireDepartmentScope(order);
            transition(order, WorkOrderStatus.PROCESSING, OperateType.DISPATCH, dto.getHandlerId(), dto.getRemark());
        } else if (current == WorkOrderStatus.PROCESSING) {
            // 转派:需转派权限,本部门派单人或当前处理人可操作
            requirePermission(PERM_TRANSFER);
            requireTransferScope(order);
            transition(order, WorkOrderStatus.PROCESSING, OperateType.TRANSFER, dto.getHandlerId(), dto.getRemark());
        } else {
            throw new BusinessException(ResultCode.CONFLICT,
                    "当前状态[" + current.getDesc() + "]不允许派单/转派");
        }
    }

    /** 处理完成:仅当前处理人,处理中 → 待验收 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void process(Long id, ProcessDTO dto) {
        WorkOrder order = requireOrder(id);
        requireHandlerSelf(order);
        transition(order, WorkOrderStatus.PENDING_ACCEPT, OperateType.PROCESS_FINISH, null, dto.getRemark());
    }

    /** 验收:仅提单人,通过 → 已完成,退回 → 处理中(返工) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long id, AcceptDTO dto) {
        WorkOrder order = requireOrder(id);
        requireSubmitterSelf(order);
        boolean approved = Boolean.TRUE.equals(dto.getApproved());
        transition(order,
                approved ? WorkOrderStatus.COMPLETED : WorkOrderStatus.PROCESSING,
                approved ? OperateType.ACCEPT_PASS : OperateType.ACCEPT_REJECT,
                null, dto.getRemark());
    }

    /** 撤回 / 取消:仅提单人,已验收之后的工单由状态机拦截 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id, WithdrawDTO dto) {
        WorkOrder order = requireOrder(id);
        requireSubmitterSelf(order);
        transition(order, WorkOrderStatus.CANCELED, OperateType.WITHDRAW, null, dto.getRemark());
    }

    /**
     * 超时自动关闭:扫描「未完结且 expire_time 已过」的工单置为已超时。
     * 幂等性靠两点保证——先校验状态机是否允许超时迁移,再依赖乐观锁
     * ({@code updateById} 影响行数 0 说明已被并发改动,跳过即可)。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeTimeout() {
        List<WorkOrder> candidates = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getStatus,
                        WorkOrderStatus.PENDING_REVIEW.getCode(),
                        WorkOrderStatus.PENDING_DISPATCH.getCode(),
                        WorkOrderStatus.PROCESSING.getCode(),
                        WorkOrderStatus.PENDING_ACCEPT.getCode())
                .isNotNull(WorkOrder::getExpireTime)
                .le(WorkOrder::getExpireTime, LocalDateTime.now())
                .last("LIMIT " + TIMEOUT_BATCH_SIZE));

        int closed = 0;
        for (WorkOrder order : candidates) {
            WorkOrderStatus current = WorkOrderStatus.fromCode(order.getStatus());
            if (!current.canTransitionTo(WorkOrderStatus.TIMEOUT)) {
                continue;
            }
            order.setStatus(WorkOrderStatus.TIMEOUT.getCode());
            order.setRemark("工单已超时,系统自动关闭");
            order.setUpdateTime(LocalDateTime.now());
            if (workOrderMapper.updateById(order) == 0) {
                // 版本冲突:已被他人修改,本轮跳过,下轮再扫
                continue;
            }
            insertLog(order.getId(), SYSTEM_OPERATOR_ID, OperateType.TIMEOUT_CLOSE,
                    current.getCode(), WorkOrderStatus.TIMEOUT.getCode(), null, order.getRemark());
            closed++;
        }
        if (closed > 0) {
            log.info("工单超时自动关闭: 本轮关闭 {} 条", closed);
        }
        return closed;
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 通用状态流转:状态机校验 → 乐观锁更新主表 → 写操作日志。
     * 主表更新影响行数为 0 视为版本冲突,直接抛 409 让前端刷新重试。
     *
     * @param order       已从库中读出的工单(带最新 version)
     * @param target      目标状态
     * @param operateType 操作事件类型
     * @param toUserId    目标处理人ID(派单/转派时传,其余传 null 表示不改动)
     * @param remark      操作备注,可空
     */
    private void transition(WorkOrder order, WorkOrderStatus target, OperateType operateType,
                            Long toUserId, String remark) {
        transition(order, target, operateType, toUserId, remark, null);
    }

    /**
     * 通用状态流转(带字段修改器)的重载:在状态赋值之后、更新落库之前,
     * 由调用方通过 {@code mutator} 追加要一并修改的字段(如「重新提交」时改标题/描述)。
     * 这样「状态流转 + 内容修改」仍是同一次带版本校验的更新,不会产生两次写。
     *
     * @param mutator 额外的字段修改逻辑,可空
     */
    private void transition(WorkOrder order, WorkOrderStatus target, OperateType operateType,
                            Long toUserId, String remark, Consumer<WorkOrder> mutator) {
        WorkOrderStatus current = WorkOrderStatus.fromCode(order.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "当前状态[" + current.getDesc() + "]不允许该操作");
        }

        order.setStatus(target.getCode());
        if (toUserId != null) {
            order.setHandlerId(toUserId);
        }
        if (remark != null) {
            order.setRemark(remark);
        }
        if (mutator != null) {
            mutator.accept(order);
        }
        // 显式刷新 update_time:updateById 会写回读取时的旧值,覆盖 ON UPDATE CURRENT_TIMESTAMP
        order.setUpdateTime(LocalDateTime.now());

        if (workOrderMapper.updateById(order) == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "工单已被他人修改,请刷新后重试");
        }

        insertLog(order.getId(), UserContext.getUserId(), operateType,
                current.getCode(), target.getCode(), toUserId, remark);
        log.info("工单流转: id={}, {} -> {}, operateType={}",
                order.getId(), current.getDesc(), target.getDesc(), operateType.getDesc());
    }

    /** 写一条操作日志 */
    private void insertLog(Long orderId, Long operatorId, OperateType operateType,
                           Integer fromStatus, Integer toStatus, Long toUserId, String remark) {
        workOrderOperateLogMapper.insert(WorkOrderOperateLog.builder()
                .orderId(orderId)
                .operatorId(operatorId)
                .operateType(operateType.getCode())
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .toUserId(toUserId)
                .remark(remark)
                .build());
    }

    /**
     * 列表数据范围:管理员不限制;其余按角色取并集。
     * 无任何数据范围角色时用「恒假条件」返回空集,避免越权看到他人数据。
     */
    private void applyListScope(LambdaQueryWrapper<WorkOrder> wrapper) {
        Set<String> roles = currentRoles();
        if (roles.contains(ROLE_ADMIN)) {
            return;
        }
        Long userId = UserContext.getUserId();
        Long departmentId = UserContext.getDepartmentId();
        boolean bySubmitter = roles.contains(ROLE_SUBMITTER);
        boolean byDepartment = roles.contains(ROLE_REVIEWER) || roles.contains(ROLE_DISPATCHER);
        boolean byHandler = roles.contains(ROLE_HANDLER);

        if (!bySubmitter && !byDepartment && !byHandler) {
            // 没有数据范围角色:直接构造不可能成立的条件
            wrapper.eq(WorkOrder::getId, -1L);
            return;
        }

        wrapper.and(w -> {
            boolean used = false;
            if (bySubmitter) {
                w.eq(WorkOrder::getUserId, userId);
                used = true;
            }
            if (byDepartment) {
                if (used) {
                    w.or();
                }
                w.eq(WorkOrder::getDepartmentId, departmentId);
                used = true;
            }
            if (byHandler) {
                if (used) {
                    w.or();
                }
                w.eq(WorkOrder::getHandlerId, userId);
            }
        });
    }

    /** 详情可见范围:管理员全可见;提单人/本部门/处理人各按自身维度可见 */
    private void requireViewScope(WorkOrder order) {
        Set<String> roles = currentRoles();
        if (roles.contains(ROLE_ADMIN)) {
            return;
        }
        Long userId = UserContext.getUserId();
        Long departmentId = UserContext.getDepartmentId();
        boolean visible = (roles.contains(ROLE_SUBMITTER) && userId.equals(order.getUserId()))
                || ((roles.contains(ROLE_REVIEWER) || roles.contains(ROLE_DISPATCHER))
                        && departmentId != null && departmentId.equals(order.getDepartmentId()))
                || (roles.contains(ROLE_HANDLER) && userId.equals(order.getHandlerId()));
        if (!visible) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该工单");
        }
    }

    /** 审核 / 派单必须在本部门范围内(管理员例外) */
    private void requireDepartmentScope(WorkOrder order) {
        if (currentRoles().contains(ROLE_ADMIN)) {
            return;
        }
        Long departmentId = UserContext.getDepartmentId();
        if (departmentId == null || !departmentId.equals(order.getDepartmentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作其他部门的工单");
        }
    }

    /** 转派范围:本部门派单人或当前处理人(管理员例外) */
    private void requireTransferScope(WorkOrder order) {
        if (currentRoles().contains(ROLE_ADMIN)) {
            return;
        }
        Long userId = UserContext.getUserId();
        Long departmentId = UserContext.getDepartmentId();
        boolean allowed = (departmentId != null && departmentId.equals(order.getDepartmentId()))
                || userId.equals(order.getHandlerId());
        if (!allowed) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权转派该工单");
        }
    }

    /** 必须是提单人本人 */
    private void requireSubmitterSelf(WorkOrder order) {
        if (!UserContext.getUserId().equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有提单人本人可以执行该操作");
        }
    }

    /** 必须是当前处理人本人 */
    private void requireHandlerSelf(WorkOrder order) {
        if (order.getHandlerId() == null || !UserContext.getUserId().equals(order.getHandlerId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有当前处理人可以执行该操作");
        }
    }

    /** 校验当前用户是否拥有指定权限(用于派单/转派这类注解无法表达的动态判权) */
    private void requirePermission(String permCode) {
        if (!UserContext.hasPermission(permCode)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "缺少操作权限: " + permCode);
        }
    }

    /** 校验目标处理人存在 */
    private void requireHandlerExists(Long handlerId) {
        if (userMapper.selectById(handlerId) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "指定的处理人不存在");
        }
    }

    /** 按ID取工单,不存在抛 404 */
    private WorkOrder requireOrder(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工单不存在");
        }
        return order;
    }

    /** 当前登录用户的角色码集合,未登录或为空时返回空集 */
    private Set<String> currentRoles() {
        UserAuth auth = UserContext.get();
        if (auth == null || auth.getRoleCodes() == null) {
            return Set.of();
        }
        return auth.getRoleCodes();
    }

    /** 生成工单编号:WO + 毫秒级时间戳 + 3 位随机数,配合 uk_order_no 保证唯一 */
    private String generateOrderNo() {
        return "WO" + LocalDateTime.now().format(ORDER_NO_FORMATTER)
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    /** 计算超时时间:未指定则默认 {@value #DEFAULT_EXPIRE_HOURS} 小时后;指定但不能早于当前时间 */
    private LocalDateTime resolveExpireTime(LocalDateTime expireTime) {
        if (expireTime == null) {
            return LocalDateTime.now().plusHours(DEFAULT_EXPIRE_HOURS);
        }
        if (expireTime.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "超时时间必须晚于当前时间");
        }
        return expireTime;
    }

    /** 主表转列表视图 */
    private WorkOrderVO toVO(WorkOrder order) {
        WorkOrderVO vo = new WorkOrderVO();
        BeanUtils.copyProperties(order, vo);
        vo.setStatusDesc(WorkOrderStatus.fromCode(order.getStatus()).getDesc());
        return vo;
    }
}
