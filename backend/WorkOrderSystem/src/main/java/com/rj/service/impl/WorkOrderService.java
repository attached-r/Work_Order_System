package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rj.cache.CacheClient;
import com.rj.common.LockConstants;
import com.rj.common.PageResult;
import com.rj.common.RedisConstants;
import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.common.UserContext;
import com.rj.config.CacheProperties;
import com.rj.config.RedisLockProperties;
import com.rj.exception.BusinessException;
import com.rj.lock.RedisLockHelper;
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
import com.rj.mq.NotifyMessage;
import com.rj.mq.NotifyOutboxWriter;
import com.rj.service.IWorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 资源申请工单类型常量(order_type=2 时启用同资源并发互斥) */
    private static final int ORDER_TYPE_RESOURCE_APPLY = 2;

    /** 编号日期部分格式:yyyyMMdd */
    private static final DateTimeFormatter ORDER_NO_DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 日内序号补零格式:序号不足 6 位左补 0,如 000001 */
    private static final String ORDER_NO_SEQ_FORMAT = "%06d";

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderResourceMapper workOrderResourceMapper;
    private final WorkOrderOperateLogMapper workOrderOperateLogMapper;
    private final UserMapper userMapper;
    private final NotifyOutboxWriter notifyOutboxWriter;
    /** 模块四:缓存统一入口(详情缓存读写 + 写失效) */
    private final CacheClient cacheClient;
    /** 模块四:Redisson 锁封装(编号生成 / 资源申请互斥) */
    private final RedisLockHelper redisLockHelper;
    /** 模块四:缓存策略参数(TTL 等) */
    private final CacheProperties cacheProperties;
    /** 模块四:分布式锁行为参数(wait / lease) */
    private final RedisLockProperties lockProperties;
    /** 日内序号 INCR 用字符串模板(序号值与缓存值序列化无关,故不复用 RedisTemplate<String,Object>) */
    private final StringRedisTemplate stringRedisTemplate;
    /**
     * 编程式事务模板。
     * <p>
     * {@code create()} 需要「锁在事务外层」——若锁落在 {@code @Transactional} 内部,
     * 它会在事务提交前就释放,资源互斥形同虚设。而 create() 自身又不能挂注解式事务,
     * 故用本模板把「数据库写入」显式包成一个事务边界,让锁留在事务之外。
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建工单(模块四改造)。
     * <p>
     * 与模块二的区别只在「资源申请」一类:当 {@code orderType=2} 且带资源明细时,
     * 按「部门 + 资源类别 + 资源名称」加 Redisson MultiLock,锁内校验「同部门不存在进行中的
     * 同类资源申请」;其余类型行为不变。
     * <p>
     * <b>为什么锁在事务外层?</b> 若把锁放在 {@code @Transactional} <b>内部</b>,锁会在方法返回
     * (事务提交前)就释放,下一个并发请求会在前一个事务<b>尚未提交</b>时通过校验,互斥形同虚设。
     * 因此这里刻意<b>不挂</b> {@code @Transactional},改用 {@link TransactionTemplate} 把落库
     * 包成一个显式事务,让锁的生命周期 ≥ 事务的生命周期。
     */
    @Override
    public Long create(WorkOrderCreateDTO dto) {
        Long departmentId = UserContext.getDepartmentId();
        // 部门是工单数据隔离的基础,无部门的账号(如全局 ADMIN)不允许提单
        if (departmentId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前用户未归属部门,无法提交工单");
        }

        if (isResourceApply(dto)) {
            // 一单多资源 → 多把锁;排序 + 去重在 helper 内完成,防死锁
            List<String> lockKeys = resourceLockKeys(distinctResourceKeys(dto), departmentId);
            return redisLockHelper.executeWithMultiLock(lockKeys,
                    lockProperties.getResourceWaitSeconds(), lockProperties.getResourceLeaseSeconds(),
                    () -> transactionTemplate.execute(status -> doCreate(dto, departmentId)));
        }
        return transactionTemplate.execute(status -> doCreate(dto, departmentId));
    }

    /**
     * 创建工单落库:主表与资源明细在同一事务写入,任一失败整体回滚。
     * 提单人、归属部门取自登录上下文,不接受前端传入。
     *
     * @param dto          创建参数
     * @param departmentId 当前用户部门(已在 {@link #create} 中校验非空)
     * @return 新工单ID
     */
    private Long doCreate(WorkOrderCreateDTO dto, Long departmentId) {
        // 资源申请:锁内校验「同部门不存在进行中的同类资源申请」,避免并发重复提交
        if (isResourceApply(dto)) {
            for (ResourceKey key : distinctResourceKeys(dto)) {
                if (workOrderMapper.countActiveResourceApplications(departmentId, key.type(), key.name()) > 0) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "该资源已有进行中的申请: " + key.type() + "/" + key.name());
                }
            }
        }

        Long userId = UserContext.getUserId();
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
        // 触点①:创建绕过了 transition(),必须单独接通知。此时尚未派单,handlerId 传 null
        notifyOutboxWriter.stage(NotifyMessage.of(order.getId(), order.getOrderNo(), order.getTitle(),
                order.getDepartmentId(), order.getUserId(), null, userId, OperateType.SUBMIT,
                null, WorkOrderStatus.PENDING_REVIEW.getCode(), "提交工单"));
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
            // 模块四:明细已被替换,必须「再删一次」详情缓存。
            // 上面的 transition() 已注册过一次失效,但那是「明细尚未替换」时的状态:
            // 若只删那一次,回填可能发生在「明细已替换、缓存却还没再删」的窗口里,导致读过期明细。
            cacheClient.evictAfterCommit(RedisConstants.WORKORDER_DETAIL_KEY + id);
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
     * 工单详情:主表 + 资源明细 + 操作日志,并做可见范围校验(模块四:接入逻辑过期缓存)。
     * <p>
     * 缓存的 {@code WorkOrderDetailVO} <b>不含调用者身份</b>,只描述工单本身,因此:
     * <ul>
     *   <li>命中缓存<b>仍要</b>跑一次 {@code requireViewScope}——<b>缓存只省 DB,不省鉴权</b>;</li>
     *   <li>判权改用 VO 上的 userId / departmentId / handlerId,不再回源 DB。</li>
     * </ul>
     * 最危险的反模式是「因为命中缓存就跳过判权」:那会在角色/部门变更后出现越权窗口。
     */
    @Override
    public WorkOrderDetailVO detail(Long id) {
        // 参数校验先于缓存:非法 id 连 Redis 都不进,是最便宜的穿透防护
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工单ID非法");
        }

        WorkOrderDetailVO vo = loadDetailWithCache(id);
        if (vo == null) {
            // 回源为空(或命中空值哨兵):该 id 在库中确实不存在
            throw new BusinessException(ResultCode.NOT_FOUND, "工单不存在");
        }
        requireViewScope(vo);
        return vo;
    }

    /**
     * 读穿详情缓存:逻辑过期 + 互斥重建 + 空值哨兵。
     * <p>
     * 逻辑/物理/哨兵三类 TTL 均取自 {@link CacheProperties},只在此处解析一次。
     *
     * @param id 工单ID
     * @return 详情视图;工单不存在时返回 null
     */
    private WorkOrderDetailVO loadDetailWithCache(Long id) {
        CacheProperties.WorkorderDetail detailTtl = cacheProperties.getWorkorderDetail();
        return cacheClient.getLogical(
                RedisConstants.WORKORDER_DETAIL_KEY + id,
                RedisConstants.WORKORDER_ABSENT_KEY + id,
                detailTtl.getLogicalTtlSeconds(),
                detailTtl.getPhysicalTtlSeconds(),
                cacheProperties.getAbsentTtlSeconds(),
                () -> loadDetailFromDb(id));
    }

    /**
     * 从数据库装载详情(缓存未命中时的回源逻辑):主表 + 资源明细 + 操作日志。
     *
     * @param id 工单ID
     * @return 详情视图;工单不存在时返回 null(由 {@link CacheClient} 写入空值哨兵)
     */
    private WorkOrderDetailVO loadDetailFromDb(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            return null;
        }
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
        List<NotifyMessage> notices = new ArrayList<>(candidates.size());
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
            // 模块四:该工单状态已变,详情缓存失效(每条成功关闭后各自登记,提交后统一 DEL)
            cacheClient.evictAfterCommit(RedisConstants.WORKORDER_DETAIL_KEY + order.getId());
            // 触点③:系统行为,操作人记为 0(排除操作人这一步据实为空操作)
            notices.add(NotifyMessage.of(order.getId(), order.getOrderNo(), order.getTitle(),
                    order.getDepartmentId(), order.getUserId(), order.getHandlerId(),
                    SYSTEM_OPERATOR_ID, OperateType.TIMEOUT_CLOSE,
                    current.getCode(), WorkOrderStatus.TIMEOUT.getCode(), order.getRemark()));
            closed++;
        }
        // 落库逐条(幂等键需要与工单一一对应),但只抛一个事件携带整批,
        // 让发送侧把 N 条合并成一次批量投递(见设计文档 9.3)
        notifyOutboxWriter.stage(notices);
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

        // 模块四:状态/处理人已变,详情缓存失效(事务提交后 DEL,避免「回滚却删了缓存」的无谓重建)。
        // 这是 7 个流转入口(resubmit/review/dispatch/transfer/process/accept/withdraw)的唯一汇聚点。
        cacheClient.evictAfterCommit(RedisConstants.WORKORDER_DETAIL_KEY + order.getId());

        insertLog(order.getId(), UserContext.getUserId(), operateType,
                current.getCode(), target.getCode(), toUserId, remark);
        // 触点②:7 个入口(resubmit/review/dispatch/transfer/process/accept/withdraw)的唯一汇聚点。
        // 顺序陷阱:必须放在这里——此时 order 上的 status/handlerId 才是新值。
        // 派单/转派的收件人就是 handlerId,读早了就把通知发给了上一个处理人;
        // 而验收类事件 toUserId 为 null,更只能从 order 上取现有处理人。
        notifyOutboxWriter.stage(NotifyMessage.of(order.getId(), order.getOrderNo(), order.getTitle(),
                order.getDepartmentId(), order.getUserId(), order.getHandlerId(), UserContext.getUserId(),
                operateType, current.getCode(), target.getCode(), remark));
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

    /**
     * 详情可见范围:管理员全可见;提单人/本部门/处理人各按自身维度可见。
     * <p>
     * 判权对象改为 {@link WorkOrderDetailVO}(而非 {@link WorkOrder}),因为详情走缓存后
     * 命中时手里只有 VO。VO 上同样带 userId / departmentId / handlerId,判权口径不变——
     * <b>缓存只省 DB,不省鉴权</b>。
     */
    private void requireViewScope(WorkOrderDetailVO vo) {
        Set<String> roles = currentRoles();
        if (roles.contains(ROLE_ADMIN)) {
            return;
        }
        Long userId = UserContext.getUserId();
        Long departmentId = UserContext.getDepartmentId();
        boolean visible = (roles.contains(ROLE_SUBMITTER) && userId.equals(vo.getUserId()))
                || ((roles.contains(ROLE_REVIEWER) || roles.contains(ROLE_DISPATCHER))
                        && departmentId != null && departmentId.equals(vo.getDepartmentId()))
                || (roles.contains(ROLE_HANDLER) && userId.equals(vo.getHandlerId()));
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

    /**
     * 生成工单编号(模块四改造):{@code WO + yyyyMMdd + 6 位日内自增序号}。
     * <p>
     * 旧格式是「毫秒时间戳 + 3 位随机」,毫秒内并发 &gt; 1000 时会撞,把正确性全押在
     * {@code uk_order_no} 的冲突重试上(而当时没有重试,撞了就 500)。新格式把序号交给
     * Redis {@code INCR},配合唯一键做双保险。
     * <p>
     * <b>锁的真正价值</b>:{@code INCR} 本身原子,「防重号」主要靠它 + {@code uk_order_no};
     * 锁守的是「{@code INCR} + 首次 {@code EXPIRE}」两步的原子性——否则首日第一次生成时,
     * 若在两步之间进程崩溃,会留下一个<b>永不过期</b>的序号键。索取该临界区锁失败抛 409。
     * <p>
     * 编号只需<b>唯一、不需连续</b>,故抢锁失败/事务回滚造成的序号「空洞」可接受,不为「连续」额外设计。
     */
    private String generateOrderNo() {
        return redisLockHelper.executeWithLock(
                LockConstants.WORKORDER_NO_LOCK_KEY,
                lockProperties.getOrderNoWaitSeconds(), lockProperties.getOrderNoLeaseSeconds(),
                () -> {
                    String day = LocalDate.now().format(ORDER_NO_DAY_FORMATTER);
                    String seqKey = RedisConstants.WORKORDER_NO_SEQ_KEY + day;
                    Long seq = stringRedisTemplate.opsForValue().increment(seqKey);
                    // 首次写入补过期,防止序号键「只增不删」永久驻留;跨天自然换新键
                    if (seq != null && seq == 1L) {
                        stringRedisTemplate.expire(seqKey,
                                Duration.ofSeconds(RedisConstants.WORKORDER_NO_SEQ_EXPIRE_SECONDS));
                    }
                    return "WO" + day + String.format(ORDER_NO_SEQ_FORMAT, seq == null ? 1L : seq);
                });
    }

    /** 是否资源申请工单(带资源明细的 orderType=2),判断是否启用同资源并发互斥 */
    private boolean isResourceApply(WorkOrderCreateDTO dto) {
        return dto.getOrderType() != null && dto.getOrderType() == ORDER_TYPE_RESOURCE_APPLY
                && dto.getResources() != null && !dto.getResources().isEmpty();
    }

    /**
     * 收集本次申请涉及的「资源类别 + 资源名称」去重集合。
     * <p>
     * 同一张单里重复申请同一资源,只算一个互斥目标——重复的锁名会被 helper 再去一次重,但这里
     * 先去重能让「锁内校验」也不对同一资源重复查询。
     */
    private List<ResourceKey> distinctResourceKeys(WorkOrderCreateDTO dto) {
        return dto.getResources().stream()
                .map(r -> new ResourceKey(r.getResourceType(), r.getResourceName()))
                .distinct()
                .toList();
    }

    /** 由 (type,name) 集合生成资源申请锁名列表:lock:resource:apply:{deptId}:{type}:{name} */
    private List<String> resourceLockKeys(List<ResourceKey> keys, Long departmentId) {
        return keys.stream()
                .map(k -> LockConstants.RESOURCE_APPLY_LOCK_PREFIX + departmentId + ":" + k.type() + ":" + k.name())
                .toList();
    }

    /**
     * 资源互斥目标(资源类别 + 资源名称)。
     * <p>
     * 用 record 而非 {@code String[]}:record 自带 equals/hashCode,{@code distinct()} 才能按值去重。
     */
    private record ResourceKey(String type, String name) {
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
