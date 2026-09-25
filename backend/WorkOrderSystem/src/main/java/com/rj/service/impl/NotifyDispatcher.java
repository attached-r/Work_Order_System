package com.rj.service.impl;

import com.rj.mapper.UserMapper;
import com.rj.model.enums.OperateType;
import com.rj.mq.NotifyMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通知的<strong>业务字典</strong>:收件人怎么算、文案怎么写。
 * <p>
 * 它是「业务语义」与「传输可靠性」的分界线:{@code com.rj.mq} 那条管线只管
 * 「消息可靠地到达」,至于「该发给谁、写什么话」全部收在这里。加一类通知事件
 * 只需改本类,{@code com.rj.mq} 一行不动。
 * <p>
 * 本类除「按部门查角色用户」外无副作用,便于单独推演与测试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDispatcher {

    /** 角色码(与 role.role_code 对应) */
    private static final String ROLE_REVIEWER = "REVIEWER";
    private static final String ROLE_DISPATCHER = "DISPATCHER";

    /**
     * 文案字典:类型 → (标题, 正文模板)。模板用 {@code {orderNo}} / {@code {orderTitle}}
     * / {@code {remark}} 占位,渲染后落库的是<strong>成品文本</strong>——文案改版不会
     * 追溯性改写历史通知。
     */
    private static final Map<OperateType, NotifyText> TEMPLATES = Map.ofEntries(
            Map.entry(OperateType.SUBMIT,
                    new NotifyText("新工单待审核", "工单 {orderNo}「{orderTitle}」已提交，请及时审核")),
            Map.entry(OperateType.REVIEW_PASS,
                    new NotifyText("工单待派单", "工单 {orderNo} 审核已通过，请安排处理人")),
            Map.entry(OperateType.REVIEW_REJECT,
                    new NotifyText("工单被驳回", "工单 {orderNo} 被驳回，原因：{remark}")),
            Map.entry(OperateType.DISPATCH,
                    new NotifyText("新工单已派给你", "工单 {orderNo}「{orderTitle}」已派给你，请及时处理")),
            Map.entry(OperateType.PROCESS_FINISH,
                    new NotifyText("工单待验收", "工单 {orderNo} 已处理完成，请验收")),
            Map.entry(OperateType.ACCEPT_PASS,
                    new NotifyText("验收通过", "工单 {orderNo} 已验收通过")),
            Map.entry(OperateType.ACCEPT_REJECT,
                    new NotifyText("验收退回", "工单 {orderNo} 被验收退回，请返工处理")),
            Map.entry(OperateType.TRANSFER,
                    new NotifyText("工单转派给你", "工单 {orderNo}「{orderTitle}」已转派给你")),
            Map.entry(OperateType.WITHDRAW,
                    new NotifyText("工单已取消", "工单 {orderNo} 已被取消")),
            Map.entry(OperateType.TIMEOUT_CLOSE,
                    new NotifyText("工单已超时关闭", "工单 {orderNo} 因超时被系统自动关闭"))
    );

    private final UserMapper userMapper;

    /**
     * 解析收件人。规则见设计文档 5.2,两条贯穿所有类型的额外规则:
     * <ul>
     *   <li><strong>永远排除操作人自己</strong>——没有人需要被告知「你刚做的事」;</li>
     *   <li><strong>TRANSFER 不通知原处理人</strong>——转派意味着工单离他而去,
     *       通知他只会造成困惑。这是刻意的产品判断,不是遗漏:载荷里的 handlerId
     *       已被 {@code transition} 覆盖为新处理人,原处理人<em>不在</em>收件人候选里。</li>
     * </ul>
     * 收件人为空是<strong>合法结果</strong>(例:部门里没有在职审核人),调用方必须 ACK 而非重试。
     *
     * @param message 事件载荷
     * @return 去重后的收件人ID列表,可能为空,不为 null
     */
    public List<Long> resolveRecipients(NotifyMessage message) {
        Set<Long> recipients = new LinkedHashSet<>();
        switch (operateTypeOf(message.getOperateType())) {
            case SUBMIT -> recipients.addAll(usersOfDepartment(message.getDepartmentId(), ROLE_REVIEWER));
            case REVIEW_PASS -> recipients.addAll(usersOfDepartment(message.getDepartmentId(), ROLE_DISPATCHER));
            case REVIEW_REJECT, PROCESS_FINISH -> addIfPresent(recipients, message.getCreatorUserId());
            case DISPATCH, ACCEPT_PASS, ACCEPT_REJECT, TRANSFER -> addIfPresent(recipients, message.getHandlerId());
            case WITHDRAW -> {
                // 有处理人就通知他(工单正离他而去),否则退回给本部门派单人
                if (message.getHandlerId() != null) {
                    recipients.add(message.getHandlerId());
                } else {
                    recipients.addAll(usersOfDepartment(message.getDepartmentId(), ROLE_DISPATCHER));
                }
            }
            case TIMEOUT_CLOSE -> {
                addIfPresent(recipients, message.getCreatorUserId());
                addIfPresent(recipients, message.getHandlerId());
            }
        }
        // 系统行为(超时关闭)的 operatorId=0 不是真实用户,这一步据实为空操作。
        // 保留它写出来,是为了避免日后有人把它当成遗漏而「补上」一个不存在的用户。
        recipients.remove(message.getOperatorId());
        return List.copyOf(recipients);
    }

    /**
     * 渲染文案(标题 + 正文)。未知类型直接抛异常,由消费端按「处理失败」走重试 → DLQ:
     * 生产端只会产出枚举内的码值,出现未知码值说明契约被破坏,<strong>让失败可见</strong>
     * 比静默丢一条通知负责。
     */
    public NotifyText renderText(NotifyMessage message) {
        NotifyText template = TEMPLATES.get(operateTypeOf(message.getOperateType()));
        if (template == null) {
            throw new IllegalStateException("通知类型缺少文案模板: " + message.getOperateType());
        }
        return new NotifyText(template.title(), render(template.content(), message));
    }

    /**
     * 类型码 → 中文,供读侧 {@code notifyTypeDesc} 使用。
     * <p>
     * 与 {@link #operateTypeOf} 不同,这里对未知码值<strong>宽容</strong>:读侧不能因为
     * 一行脏数据就让整页收件箱 500,拿不到标签就返回 null,这条通知照样显示。
     */
    public static String typeDesc(Integer notifyType) {
        for (OperateType type : OperateType.values()) {
            if (type.getCode().equals(notifyType)) {
                return type.getDesc();
            }
        }
        log.warn("未知的通知类型码,按无标签处理: notifyType={}", notifyType);
        return null;
    }

    /** 类型码 → 枚举,未知码值抛异常(消费端据此把消息判为处理失败) */
    private static OperateType operateTypeOf(Integer code) {
        for (OperateType type : OperateType.values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的通知类型码: " + code);
    }

    /** 按部门 + 角色查在职用户;无部门或无匹配返回空列表(调用方必须容忍空集) */
    private List<Long> usersOfDepartment(Long departmentId, String roleCode) {
        if (departmentId == null) {
            return List.of();
        }
        List<Long> userIds = userMapper.selectUserIdsByDepartmentAndRole(departmentId, roleCode);
        return userIds == null ? List.of() : userIds;
    }

    private static void addIfPresent(Set<Long> target, Long userId) {
        if (userId != null) {
            target.add(userId);
        }
    }

    /** 占位符替换;占位符对应的字段为空时替换成空串,不留下字面量 {remark} */
    private static String render(String template, NotifyMessage message) {
        return template
                .replace("{orderNo}", nullToEmpty(message.getOrderNo()))
                .replace("{orderTitle}", nullToEmpty(message.getOrderTitle()))
                .replace("{remark}", nullToEmpty(message.getRemark()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 渲染结果:标题与正文 */
    public record NotifyText(String title, String content) {
    }
}
