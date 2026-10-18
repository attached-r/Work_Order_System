package com.agentworkorder.controller;

import com.agentworkorder.perm.PermService;
import com.agentworkorder.perm.UserPerms;
import com.agentworkorder.rag.KnowledgeIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理端点（M3 §7.5）：重建与状态查询。
 *
 * <p>权限纪律：<b>agent 不另造管理员密码/令牌</b>，继续以项目一的权限为准——
 * 用 {@link PermService} 取当前用户 perms，要求包含 {@code user:manage}
 * （种子数据里只有 ADMIN 拥有）。未登录、token 失效、项目一不可达都按 fail-closed 返回 403。
 *
 * <p>RAG 关闭时整个控制器不注册（访问表现为 404），避免暴露无意义的端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/knowledge")
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeAdminController {

    /** 管理端点所需权限码：与项目一种子数据中的 ADMIN 权限一致。 */
    private static final String ADMIN_PERM = "user:manage";

    private final KnowledgeIngestService ingestService;
    private final PermService permService;

    public KnowledgeAdminController(KnowledgeIngestService ingestService, PermService permService) {
        this.ingestService = ingestService;
        this.permService = permService;
    }

    /**
     * 手动重建知识索引。
     *
     * @param force 是否忽略 manifest 的“已是最新”判断强制重建
     */
    @PostMapping("/reindex")
    public ResponseEntity<Object> reindex(
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        // 步骤 1：先鉴权；非管理员一律 403（fail-closed）
        if (!isAdmin(authorization)) {
            return forbidden();
        }

        // 步骤 2：执行重建；单飞冲突返回 409，其它失败返回 500
        try {
            return ResponseEntity.ok(ingestService.ingest(force));
        } catch (KnowledgeIngestService.IngestInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", 409, "message", e.getMessage()));
        } catch (Exception e) {
            log.warn("知识库重建失败：{}", rootMessage(e));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "知识库重建失败：" + rootMessage(e)));
        }
    }

    /** 查询知识库状态：活跃版本、chunk 数、模型/维度、上次入库时间。 */
    @GetMapping("/status")
    public ResponseEntity<Object> status(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (!isAdmin(authorization)) {
            return forbidden();
        }
        return ResponseEntity.ok(ingestService.status());
    }

    /** 用项目一的用户权限判定管理员；任何异常路径由 PermService 降级为匿名，故这里天然 fail-closed。 */
    private boolean isAdmin(String authorization) {
        String traceId = UUID.randomUUID().toString();
        UserPerms perms = permService.load(authorization, traceId);
        boolean allowed = perms != null && perms.has(ADMIN_PERM);
        if (!allowed) {
            log.info("知识库管理端点拒绝访问：trace={} user={}",
                    traceId, perms == null ? null : perms.username());
        }
        return allowed;
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", 403, "message", "需要管理员权限（" + ADMIN_PERM + "）"));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.toString();
    }
}
