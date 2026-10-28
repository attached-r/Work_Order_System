package com.agentworkorder.tool;

import com.agentworkorder.audit.AuditedToolInvoker;
import com.agentworkorder.audit.ToolResult;
import com.agentworkorder.client.ToolChangeStatusRequest;
import com.agentworkorder.client.WorkOrderToolClient;
import com.agentworkorder.metrics.AgentMetrics;
import com.agentworkorder.perm.PermService;
import com.agentworkorder.perm.ToolPermissionRegistry;
import com.agentworkorder.perm.UserPerms;
import com.agentworkorder.write.PendingWrite;
import com.agentworkorder.write.WriteConfirmationService;
import com.agentworkorder.write.WriteGuard;
import com.agentworkorder.write.WriteProperties;
import com.agentworkorder.write.WriteRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 六个工单写工具 + 一个处理人候选读工具（M4）。
 *
 * <p>统一采用<b>两阶段确认</b>：
 * <ol>
 *   <li>第一次调用不传 {@code confirmationToken}：只读一次工单详情做预览，签发一次性令牌，
 *       <b>绝不调用写接口</b>；</li>
 *   <li>用户明确同意后，带同一组参数与令牌再次调用：校验令牌与参数指纹，再请求项目一。</li>
 * </ol>
 *
 * <p>agent <b>不判断能不能写</b>：前置状态只用于预览提示，权限、数据范围、状态机、并发
 * 全部由项目一裁决。本类的本地拒绝只有四类：参数缺失、令牌无效、每轮写超限、项目一不可达。
 */
@Component
@ConditionalOnProperty(name = "agent.write.enabled", havingValue = "true", matchIfMissing = true)
public class WorkOrderWriteTools {

    /** 审计脱敏：预览响应里的 confirmationToken 只保留前 8 位 */
    private static final Pattern TOKEN_IN_BODY =
            Pattern.compile("(\"confirmationToken\"\\s*:\\s*\")([^\"]{8})[^\"]*(\")");

    private final WorkOrderToolClient toolClient;
    private final AuditedToolInvoker auditedToolInvoker;
    private final WriteConfirmationService confirmationService;
    private final WriteGuard writeGuard;
    private final WriteProperties writeProperties;
    private final ObjectMapper objectMapper;
    private final PermService permService;
    private final ToolPermissionRegistry permissionRegistry;
    private final AgentMetrics metrics;

    public WorkOrderWriteTools(WorkOrderToolClient toolClient,
                               AuditedToolInvoker auditedToolInvoker,
                               WriteConfirmationService confirmationService,
                               WriteGuard writeGuard,
                               WriteProperties writeProperties,
                               ObjectMapper objectMapper,
                               PermService permService,
                               ToolPermissionRegistry permissionRegistry,
                               AgentMetrics metrics) {
        this.toolClient = toolClient;
        this.auditedToolInvoker = auditedToolInvoker;
        this.confirmationService = confirmationService;
        this.writeGuard = writeGuard;
        this.writeProperties = writeProperties;
        this.objectMapper = objectMapper;
        this.permService = permService;
        this.permissionRegistry = permissionRegistry;
        this.metrics = metrics;
    }

    /**
     * 审核工单（写操作）。
     *
     * @param id                工单 ID
     * @param approved          true 通过（→待派单），false 驳回（→已驳回）
     * @param remark            审核意见/驳回原因，可空
     * @param confirmationToken 预览时留空；确认时传预览返回的令牌
     */
    @Tool(name = "reviewWorkOrder",
            description = "审核工单（写操作，仅对「待审核」状态有效）。"
                    + "第一次调用不要传 confirmationToken，工具会返回预览与一次性令牌；"
                    + "把预览告知用户并确认后，带同一组参数与令牌再次调用才会真正修改。"
                    + "approved=true 通过（→待派单），false 驳回（→已驳回）；approved 必填。")
    public String reviewWorkOrder(
            @ToolParam(description = "工单 ID")
            Long id,
            @ToolParam(description = "审核结论：true 通过，false 驳回；必填")
            Boolean approved,
            @ToolParam(required = false, description = "审核意见/驳回原因，可空")
            String remark,
            @ToolParam(required = false, description = "预览时留空；确认时传预览返回的 confirmationToken")
            String confirmationToken,
            ToolContext toolContext) {
        return handle("reviewWorkOrder", "REVIEW", Set.of(0),
                id, approved, null, remark, confirmationToken, toolContext);
    }

    /**
     * 派单（写操作）：待派单 → 处理中，指定处理人。
     */
    @Tool(name = "dispatchWorkOrder",
            description = "派单（写操作，仅对「待派单」状态有效）。"
                    + "第一次调用不要传 confirmationToken，返回预览与一次性令牌；用户确认后再带令牌调用。"
                    + "必须提供 handlerId：先调用 listAssignableHandlers 把姓名解析成 userId，不要猜。")
    public String dispatchWorkOrder(
            @ToolParam(description = "工单 ID")
            Long id,
            @ToolParam(description = "目标处理人 userId；必填，先用 listAssignableHandlers 查候选")
            Long handlerId,
            @ToolParam(required = false, description = "派单说明，可空")
            String remark,
            @ToolParam(required = false, description = "预览时留空；确认时传预览返回的 confirmationToken")
            String confirmationToken,
            ToolContext toolContext) {
        return handle("dispatchWorkOrder", "DISPATCH", Set.of(1),
                id, null, handlerId, remark, confirmationToken, toolContext);
    }

    /**
     * 转派（写操作）：处理中 → 处理中，更换处理人。
     */
    @Tool(name = "transferWorkOrder",
            description = "转派（写操作，仅对「处理中」状态有效，工单状态不变、只换处理人）。"
                    + "第一次调用不要传 confirmationToken，返回预览与一次性令牌；用户确认后再带令牌调用。"
                    + "必须提供 handlerId：先调用 listAssignableHandlers 查候选。")
    public String transferWorkOrder(
            @ToolParam(description = "工单 ID")
            Long id,
            @ToolParam(description = "新的处理人 userId；必填，先用 listAssignableHandlers 查候选")
            Long handlerId,
            @ToolParam(required = false, description = "转派说明，可空")
            String remark,
            @ToolParam(required = false, description = "预览时留空；确认时传预览返回的 confirmationToken")
            String confirmationToken,
            ToolContext toolContext) {
        return handle("transferWorkOrder", "TRANSFER", Set.of(2),
                id, null, handlerId, remark, confirmationToken, toolContext);
    }

    /**
     * 处理完成（写操作）：处理中 → 待验收。
     */
    @Tool(name = "processWorkOrder",
            description = "提交处理完成（写操作，仅对「处理中」状态有效）。"
                    + "第一次调用不要传 confirmationToken，返回预览与一次性令牌；用户确认后再带令牌调用。")
    public String processWorkOrder(
            @ToolParam(description = "工单 ID")
            Long id,
            @ToolParam(required = false, description = "处理结果说明，可空")
            String remark,
            @ToolParam(required = false, description = "预览时留空；确认时传预览返回的 confirmationToken")
            String confirmationToken,
            ToolContext toolContext) {
        return handle("processWorkOrder", "PROCESS", Set.of(2),
                id, null, null, remark, confirmationToken, toolContext);
    }

    /**
     * 验收（写操作）：通过 → 已完成，退回 → 处理中。
     */
    @Tool(name = "acceptWorkOrder",
            description = "验收工单（写操作，仅对「待验收」状态有效）。"
                    + "第一次调用不要传 confirmationToken，返回预览与一次性令牌；用户确认后再带令牌调用。"
                    + "approved=true 通过（→已完成），false 退回（→处理中）；approved 必填。")
    public String acceptWorkOrder(
            @ToolParam(description = "工单 ID")
            Long id,
            @ToolParam(description = "验收结论：true 通过，false 退回；必填")
            Boolean approved,
            @ToolParam(required = false, description = "验收意见/退回原因，可空")
            String remark,
            @ToolParam(required = false, description = "预览时留空；确认时传预览返回的 confirmationToken")
            String confirmationToken,
            ToolContext toolContext) {
        return handle("acceptWorkOrder", "ACCEPT", Set.of(3),
                id, approved, null, remark, confirmationToken, toolContext);
    }

    /**
     * 撤回/取消（写操作）：待审核/待派单/处理中/已驳回 → 已取消。
     */
    @Tool(name = "withdrawWorkOrder",
            description = "撤回或取消工单（写操作，仅对「待审核/待派单/处理中/已驳回」有效）。"
                    + "第一次调用不要传 confirmationToken，返回预览与一次性令牌；用户确认后再带令牌调用。")
    public String withdrawWorkOrder(
            @ToolParam(description = "工单 ID")
            Long id,
            @ToolParam(required = false, description = "撤回/取消原因，可空")
            String remark,
            @ToolParam(required = false, description = "预览时留空；确认时传预览返回的 confirmationToken")
            String confirmationToken,
            ToolContext toolContext) {
        return handle("withdrawWorkOrder", "WITHDRAW", Set.of(0, 1, 2, 5),
                id, null, null, remark, confirmationToken, toolContext);
    }

    /**
     * 可指派处理人候选（只读）：派单/转派前把姓名解析成 userId。
     */
    @Tool(name = "listAssignableHandlers",
            description = "只读工具：查询当前用户可指派的处理人候选（仅本部门、启用、HANDLER 角色）。"
                    + "派单/转派前先用它把姓名解析成 userId；不要凭空猜 handlerId。")
    public String listAssignableHandlers(
            @ToolParam(required = false, description = "姓名或账号关键字，可空；不传返回全部候选")
            String keyword,
            ToolContext toolContext) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword == null ? "" : keyword);
        return auditedToolInvoker.invoke("listAssignableHandlers", args, toolContext, () -> {
            try {
                return toolClient.listHandlers(keyword, tokenOf(toolContext));
            } catch (Exception e) {
                return errorJson(500, "查询处理人失败：" + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // 预览 / 确认的统一骨架
    // ------------------------------------------------------------------

    /**
     * 六个写工具共用的处理骨架：按是否带令牌分流到预览或确认，并统一审计与错误体。
     */
    private String handle(String toolName, String action, Set<Integer> allowedFrom,
                          Long id, Boolean approved, Long handlerId, String remark,
                          String confirmationToken, ToolContext toolContext) {
        boolean confirm = StringUtils.hasText(confirmationToken);
        Map<String, Object> args = auditArgs(confirm, id, action, approved, handlerId, remark, confirmationToken);

        String result = auditedToolInvoker.invoke(toolName, args, toolContext, () -> {
            try {
                return confirm
                        ? confirm(toolName, action, id, approved, handlerId, remark, confirmationToken, toolContext)
                        : preview(action, allowedFrom, id, approved, handlerId, remark, toolContext);
            } catch (WriteRejectedException e) {
                // 本地拒绝（参数缺失/令牌失效/每轮超限/权限复核失败）：转成错误体让模型如实转述
                return errorJson(e.getCode(), e.getMessage());
            } catch (Exception e) {
                // 传输层异常：写路径绝不自动重试，先让用户查最新状态
                return confirm
                        ? errorJson(500, "写操作结果未知，请先查询工单当前状态再决定是否重试：" + e.getMessage())
                        : errorJson(500, "无法读取工单，预览未签发令牌：" + e.getMessage());
            }
        }, WorkOrderWriteTools::maskTokenInBody);

        // M5：写操作指标；与审计口径一致（code+message 即 ERROR）
        String resultStatus = ToolResult.inspectCode(result) == null ? "SUCCESS" : "ERROR";
        metrics.writeAction(action, confirm ? "confirm" : "preview", resultStatus);
        return result;
    }

    /**
     * 预览：读一次工单详情（绝不写），签发令牌并返回给模型。
     * <p>
     * 状态不满足前置条件也照常预览——让用户看到真实状态；是否放行由项目一决定。
     */
    private String preview(String action, Set<Integer> allowedFrom, Long id,
                           Boolean approved, Long handlerId, String remark,
                           ToolContext toolContext) throws Exception {
        // 步骤 1：动作参数校验。缺 approved 必须是 400，绝不能静默当成 false
        if (id == null || id <= 0) {
            throw new WriteRejectedException(400, "工单 ID 非法");
        }
        if (requiresApproved(action) && approved == null) {
            throw new WriteRejectedException(400, actionDesc(action) + "必须提供 approved（true 通过 / false 驳回或退回）");
        }
        if (requiresHandler(action) && handlerId == null) {
            throw new WriteRejectedException(400, actionDesc(action) + "必须提供 handlerId，请先调用 listAssignableHandlers 查候选");
        }

        // 步骤 2：读最新工单详情；项目一返回错误体（401/403/404）时原样交回，不签发令牌
        String body = toolClient.queryWorkOrder(id, tokenOf(toolContext));
        if (!StringUtils.hasText(body)) {
            throw new WriteRejectedException(500, "工单详情返回为空，无法预览");
        }
        JsonNode root = objectMapper.readTree(body);
        if (isErrorBody(root)) {
            return body;
        }

        // 步骤 3：以本次实时读取的状态为锚点，签发绑定「会话+用户+工单+动作+参数」的令牌
        int status = root.path("status").asInt(-1);
        int targetStatus = targetStatus(action, approved);
        String token = confirmationService.issue(
                sessionIdOf(toolContext), userIdOf(toolContext), id, action, status,
                paramsOf(handlerId, approved, remark));

        // 步骤 4：只投影预览必要字段，content/资源/日志一律不进上下文
        ObjectNode out = objectMapper.createObjectNode();
        out.put("mode", "PREVIEW");
        out.put("confirmationToken", token);
        out.put("expiresInSeconds", writeProperties.getConfirmTtlSeconds());

        ObjectNode order = out.putObject("order");
        order.put("id", id);
        order.put("orderNo", textOrNull(root, "orderNo"));
        order.put("title", textOrNull(root, "title"));
        order.put("status", status);
        order.put("statusDesc", root.path("statusDesc").asText(statusDesc(status)));
        if (root.hasNonNull("handlerId")) {
            order.put("handlerId", root.get("handlerId").asLong());
        }

        out.put("action", action);
        ObjectNode intent = out.putObject("intent");
        if (approved != null) {
            intent.put("approved", approved);
        }
        if (handlerId != null) {
            intent.put("handlerId", handlerId);
        }
        if (StringUtils.hasText(remark)) {
            intent.put("remark", normalizeRemark(remark));
        }

        out.put("observedFromStatus", status);
        out.put("targetStatus", targetStatus);
        out.put("targetStatusDesc", statusDesc(targetStatus));

        String message = "这是预览，尚未修改工单。请向用户原样说明工单号、标题、当前状态、"
                + "将执行的动作和目标状态；用户明确同意后，用相同参数并携带 confirmationToken 再次调用。";
        if (!allowedFrom.contains(status)) {
            message += " 注意：当前状态不满足该动作的前置条件，提交将被项目一拒绝。";
        }
        out.put("message", message);
        return objectMapper.writeValueAsString(out);
    }

    /**
     * 确认：校验并消费令牌 → 每轮写限流 → 请求项目一。
     * <p>
     * 令牌消费后即作废；即使项目一拒绝，也不能拿同一个令牌重试。
     */
    private String confirm(String toolName, String action, Long id, Boolean approved, Long handlerId,
                           String remark, String confirmationToken, ToolContext toolContext) throws Exception {
        if (id == null || id <= 0) {
            throw new WriteRejectedException(400, "工单 ID 非法");
        }

        // 步骤 1：跳过缓存重查权限。失败就不消费令牌，用户还能拿同一个令牌重试。
        // 这里不能只信 60 秒的 perms 缓存：管理员刚收回权限时，写工具可能还「看得见」。
        UserPerms fresh = permService.loadFresh(tokenOf(toolContext), traceIdOf(toolContext));
        if (!permissionRegistry.visibleTo(toolName, fresh)) {
            throw new WriteRejectedException(403,
                    "权限校验未通过（可能已被收回或权限服务暂不可用），写操作未执行");
        }

        // 步骤 2：令牌校验（会话/用户/动作/工单/参数指纹全部比对）
        PendingWrite pending = confirmationService.consume(
                confirmationToken, sessionIdOf(toolContext), userIdOf(toolContext), action, id,
                paramsOf(handlerId, approved, remark));

        // 步骤 3：每轮真实写次数限流（预览不计）
        writeGuard.checkAndCount(traceIdOf(toolContext));

        // 步骤 4：带预览时观察到的状态去请求项目一，状态漂移会 409 且不写
        ToolChangeStatusRequest request = new ToolChangeStatusRequest(
                action, pending.observedFromStatus(), handlerId, approved, normalizeRemark(remark));
        return toolClient.changeStatus(id, request, tokenOf(toolContext));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 动作是否必须提供 approved */
    private static boolean requiresApproved(String action) {
        return "REVIEW".equals(action) || "ACCEPT".equals(action);
    }

    /** 动作是否必须提供 handlerId */
    private static boolean requiresHandler(String action) {
        return "DISPATCH".equals(action) || "TRANSFER".equals(action);
    }

    /** 动作中文名（用于参数错误提示） */
    private static String actionDesc(String action) {
        return switch (action) {
            case "REVIEW" -> "审核";
            case "DISPATCH" -> "派单";
            case "TRANSFER" -> "转派";
            case "PROCESS" -> "处理完成";
            case "ACCEPT" -> "验收";
            case "WITHDRAW" -> "撤回/取消";
            default -> action;
        };
    }

    /** 动作 + 结论 → 预期目标状态（只用于预览展示，不用于放行） */
    private static int targetStatus(String action, Boolean approved) {
        return switch (action) {
            case "REVIEW" -> Boolean.TRUE.equals(approved) ? 1 : 5;
            case "DISPATCH", "TRANSFER" -> 2;
            case "PROCESS" -> 3;
            case "ACCEPT" -> Boolean.TRUE.equals(approved) ? 4 : 2;
            case "WITHDRAW" -> 6;
            default -> -1;
        };
    }

    /** 状态码 → 中文名（本地仅用于预览；权威状态名仍取自项目一） */
    private static String statusDesc(int status) {
        return switch (status) {
            case 0 -> "待审核";
            case 1 -> "待派单";
            case 2 -> "处理中";
            case 3 -> "待验收";
            case 4 -> "已完成";
            case 5 -> "已驳回";
            case 6 -> "已取消";
            case 7 -> "已超时";
            default -> "未知";
        };
    }

    /** 动作参数（用于指纹）；remark 归一成「非空白或 null」，避免空串与 null 算出不同指纹 */
    private static Map<String, Object> paramsOf(Long handlerId, Boolean approved, String remark) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("handlerId", handlerId);
        params.put("approved", approved);
        params.put("remark", normalizeRemark(remark));
        return params;
    }

    private static String normalizeRemark(String remark) {
        return StringUtils.hasText(remark) ? remark.trim() : null;
    }

    /** 审计入参；确认时令牌只留前缀 */
    private static Map<String, Object> auditArgs(boolean confirm, Long id, String action,
                                                 Boolean approved, Long handlerId,
                                                 String remark, String confirmationToken) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("mode", confirm ? "confirm" : "preview");
        args.put("id", id);
        args.put("action", action);
        if (approved != null) {
            args.put("approved", approved);
        }
        if (handlerId != null) {
            args.put("handlerId", handlerId);
        }
        if (StringUtils.hasText(remark)) {
            args.put("remark", remark);
        }
        if (confirm) {
            args.put("token", maskTokenValue(confirmationToken));
        }
        return args;
    }

    /** 审计摘要脱敏：只保留 confirmationToken 前 8 位 */
    private static String maskTokenInBody(String body) {
        if (body == null) {
            return null;
        }
        return TOKEN_IN_BODY.matcher(body).replaceAll("$1$2…$3");
    }

    private static String maskTokenValue(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        return token.length() <= 8 ? token + "…" : token.substring(0, 8) + "…";
    }

    private static boolean isErrorBody(JsonNode root) {
        return root != null && root.isObject() && root.hasNonNull("code") && root.hasNonNull("message");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 构造与项目一口径一致的错误体，交给模型转述 */
    private String errorJson(int code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"code\":500,\"message\":\"内部错误\"}";
        }
    }

    private static String tokenOf(ToolContext toolContext) {
        return ToolContextKeys.stringValue(toolContext, ToolContextKeys.AUTHORIZATION, "");
    }

    private static String sessionIdOf(ToolContext toolContext) {
        return ToolContextKeys.stringValue(toolContext, ToolContextKeys.SESSION_ID, "");
    }

    private static Long userIdOf(ToolContext toolContext) {
        return ToolContextKeys.longValue(toolContext, ToolContextKeys.USER_ID);
    }

    private static String traceIdOf(ToolContext toolContext) {
        return ToolContextKeys.stringValue(toolContext, ToolContextKeys.TRACE_ID, "");
    }
}
