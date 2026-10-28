package com.agentworkorder.perm;

import com.agentworkorder.client.WorkOrderToolClient;
import com.agentworkorder.config.PermProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * 用户权限加载服务：调项目一 {@code /tool/user/info} 取 {@code perms}，并做 TTL 缓存。
 *
 * <p>三条设计要点（M2 建立，M5 把缓存换成 Redis）：
 * <ol>
 *   <li><b>不发明权限</b>——权限码一律原样抄自项目一，agent 不推导、不合并、不猜测；</li>
 *   <li><b>fail-closed</b>——未带 token / token 失效（返回 401/403 错误体）/ 项目一不可达（异常），
 *       三者一律降级为 {@link UserPerms#ANONYMOUS}（空权限）。给不出清单，就不放行；</li>
 *   <li><b>缓存</b>——按 token 的 SHA-256 做 key（<b>不落明文 token</b>），TTL 取
 *       {@code agent.perm.cache-ttl-seconds}；设为 0 时退化为「每次现查、不缓存」。</li>
 * </ol>
 *
 * <p>M5 起缓存实现由 {@link PermCache} 决定（memory / redis），本类只关心「取、回源、写」。
 */
@Slf4j
@Service
public class PermService {

    private final WorkOrderToolClient client;
    private final PermProperties properties;
    private final ObjectMapper objectMapper;
    private final PermCache cache;

    public PermService(WorkOrderToolClient client, PermProperties properties,
                       ObjectMapper objectMapper, PermCache cache) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    /**
     * 取某 token 对应的用户权限（先查缓存）。
     *
     * @param authToken 用户 JWT（完整 Authorization 头值），可空
     * @param traceId   本轮追踪 id，仅用于日志
     * @return 权限快照；任何异常路径都返回 {@link UserPerms#ANONYMOUS}
     */
    public UserPerms load(String authToken, String traceId) {
        if (!StringUtils.hasText(authToken)) {
            // 未登录：无需打扰项目一，直接匿名
            return UserPerms.ANONYMOUS;
        }

        long ttlSeconds = properties.getCacheTtlSeconds();
        String key = sha256(authToken);

        if (ttlSeconds > 0) {
            UserPerms cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        UserPerms perms = fetch(authToken, traceId);
        if (ttlSeconds > 0) {
            cache.put(key, perms, Duration.ofSeconds(ttlSeconds));
        }
        return perms;
    }

    /**
     * 跳过缓存，强制回源（M5 §8.2：写操作确认前用）。
     *
     * <p>perms 缓存 60 秒，管理员刚收回权限时，用户可能在这 60 秒内仍「看得见」写工具。
     * 项目一最终会 403，但 agent 侧在确认前再查一次更稳。
     *
     * <p>拿不到时仍然返回 {@link UserPerms#ANONYMOUS}（fail-closed），
     * 由调用方决定提示「权限已变更」还是「权限服务暂不可用」。
     */
    public UserPerms loadFresh(String authToken, String traceId) {
        if (!StringUtils.hasText(authToken)) {
            return UserPerms.ANONYMOUS;
        }
        return fetch(authToken, traceId);
    }

    /** 实打实调一次项目一；任何异常降级为匿名。 */
    private UserPerms fetch(String authToken, String traceId) {
        try {
            return parse(client.getUserInfo(authToken));
        } catch (Exception e) {
            log.warn("取用户权限失败，降级为匿名（fail-closed）：trace={} err={}", traceId, e.toString());
            return UserPerms.ANONYMOUS;
        }
    }

    /** 解析 {@code UserVO} 裸 JSON；若是 {@code {code,message}} 错误体则视为匿名。 */
    private UserPerms parse(String body) {
        if (!StringUtils.hasText(body)) {
            return UserPerms.ANONYMOUS;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return UserPerms.ANONYMOUS;
            }
            // 顶层同时含 code+message → 项目一的错误体（401/403…），视为匿名
            if (root.hasNonNull("code") && root.hasNonNull("message")) {
                log.info("用户信息接口返回错误体，视为匿名：code={}", root.get("code").asInt());
                return UserPerms.ANONYMOUS;
            }
            Long userId = root.hasNonNull("userId") ? root.get("userId").asLong() : null;
            String username = root.hasNonNull("username") ? root.get("username").asText() : null;
            Set<String> permCodes = new HashSet<>();
            JsonNode permsNode = root.get("perms");
            if (permsNode != null && permsNode.isArray()) {
                permsNode.forEach(node -> {
                    if (node.isTextual()) {
                        permCodes.add(node.asText());
                    }
                });
            }
            return new UserPerms(userId, username, Set.copyOf(permCodes));
        } catch (Exception e) {
            log.warn("解析用户权限失败，降级为匿名（fail-closed）：{}", e.toString());
            return UserPerms.ANONYMOUS;
        }
    }

    /** token → SHA-256 十六进制串，作为缓存 key（不落明文 token）。 */
    private static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // 理论上不会发生；退化用 hashCode，仍不落明文
            return Integer.toHexString(token.hashCode());
        }
    }
}
