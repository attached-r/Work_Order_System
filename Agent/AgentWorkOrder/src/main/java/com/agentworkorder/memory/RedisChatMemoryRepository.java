package com.agentworkorder.memory;

import com.agentworkorder.redis.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 版会话记忆仓库（M5）。
 *
 * <p>只实现 ChatMemoryRepository 的四个方法；「最近 N 条」仍由 MessageWindowChatMemory 负责。
 *
 * <p>值的结构（带版本号，方便将来改结构时兼容）：
 * <pre>
 * {
 *   "version": 1,
 *   "messages": [
 *     {"role": "user", "text": "..."},
 *     {"role": "assistant", "text": "..."}
 *   ]
 * }
 * </pre>
 *
 * <p>降级原则：<b>记忆是体验，不是安全边界</b>。任何 Redis / JSON 异常都只告警，
 * 读不到就当没有记忆，写失败也不影响本轮对话。
 */
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    /** 当前快照结构版本；结构不兼容时直接当空，避免把旧格式硬解析成错消息 */
    private static final int SNAPSHOT_VERSION = 1;

    private final StringRedisTemplate redis;
    private final JsonCodec jsonCodec;
    private final String keyPrefix;        //前缀
    private final Duration ttl;

    public RedisChatMemoryRepository(StringRedisTemplate redis, JsonCodec jsonCodec,
                                        String keyPrefix, Duration ttl) {
        this.redis = redis;
        this.jsonCodec = jsonCodec;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    /**
     * 管理 / 运维用：截取前缀后返回会话id列表
     * <p>用 SCAN 而不是 KEYS：KEYS 在大 key 空间会阻塞 Redis。
     * @return 去除key前缀后的会话id集合
     */
    @Override
    public List<String> findConversationIds() {
        try{
            List<String> ids = redis.execute((RedisCallback<List<String>>) connection ->{
                List<String> result = new ArrayList<>(); // 构造返回集合
                // 1. 获取扫描对象
                ScanOptions options = ScanOptions.scanOptions()
                        .match(keyPrefix + "*") // 客户端过滤
                        .count(200)  // 尽量一次200个
                        .build();
                // 2. 通过连接 拿到底层命令 执行scan
                try(Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    cursor.forEachRemaining(key -> { // Lambda表达式 遍历每一个字节数组key
                        String raw = new String(key, StandardCharsets.UTF_8); // 转为String对象
                        result.add(raw.substring(keyPrefix.length())); // 截取纯id
                    });
                }
                // 3. 返回结果集合
                return  result;
            });
            return ids == null ? List.of() : ids; //如果为空 返回List 不可变对象,避免上层指针为空
        } catch (Exception e) {
            log.warn("扫描会话记忆失败(不影响对话):{}",e.toString());
            return  List.of();
        }
    }
    /**
     * 根据会话ID读取该会话下的消息列表
     * <p>从Redis String中读取MemorySnapshot快照JSON，校验版本合法性，转换为上层Message对象
     * <p>异常兜底策略：Redis异常、JSON损坏、版本不匹配时，直接返回空列表，不中断对话主流程
     * @param conversationId 会话唯一标识
     * @return 会话消息列表，无数据/异常时返回空不可变集合
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        try{
            // 1. 先拿到 json数据（必须和 saveAll 用同一个 key：keyPrefix + conversationId）
            String json =  redis.opsForValue().get(key(conversationId));
            // 判断json为空串或者null，直接返回空集合
            if(!StringUtils.hasText(json)){
                return  List.of();
            }

            // 2. 反序列化为快照对象
            MemorySnapshot snapshot = jsonCodec.fromJson(json, MemorySnapshot.class);
            // 校验快照：快照为null / 版本号不一致（旧版本快照不兼容） / 消息数组为null → 返回空
            if(snapshot == null || snapshot.version() != SNAPSHOT_VERSION || snapshot.messages() == null){
                return  List.of();
            }

            // 3. 返回消息列表
            List<Message> messages = new ArrayList<>(snapshot.messages().size());// 初始化结果集合 预分配容量大小
            for(StoredMessage stored : snapshot.messages()){
                Message message = toMessage(stored);
                if(message!=null){
                    messages.add(message);
                }
            }
            return  messages;
        } catch (Exception e) {
            // Redis 挂了 / JSON 坏了：当没有记忆，不要让对话失败
            log.warn("读取会话记忆失败，按无记忆处理：conversation={} err={}",
                    conversationId, e.toString());
            return List.of();
        }
    }

    /**
     * 批量保存会话消息快照到Redis
     * <p>将上层业务Message列表转换为存储模型StoredMessage，过滤不需要持久化的消息；
     * 组装带版本号的MemorySnapshot，序列化为JSON存入Redis String，并设置过期时间
     * <p>容错策略：写入异常仅打印警告日志，不抛出异常阻断对话，只是丢失本次上下文记忆
     * @param conversationId 会话唯一标识
     * @param messages 待持久化的业务消息列表
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<StoredMessage> stored = new ArrayList<>();
            for (Message message : messages) {

                // 1. 将业务消息类型转换为存储用的角色字符串
                String role = roleOf(message.getMessageType());
                if (role == null) {
                    continue;   // TOOL 消息不落库
                }

                // 2. 获取消息文本内容
                String text = message.getText();
                if (!StringUtils.hasText(text)) {
                    continue;   // 只有 tool call、没有文本的 assistant 消息不落库
                }
                stored.add(new StoredMessage(role, text));
            }

            MemorySnapshot snapshot = new MemorySnapshot(SNAPSHOT_VERSION, stored);
            // 序列化快照为JSON字符串，写入Redis，设置TTL过期时间
            redis.opsForValue().set(key(conversationId), jsonCodec.toJson(snapshot), ttl);
        } catch (Exception e) {
            log.warn("写入会话记忆失败，本轮对话继续（下次会少一段上下文）：conversation={} err={}",
                    conversationId, e.toString());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            log.warn("删除会话记忆失败：conversation={} err={}", conversationId, e.toString());
        }
    }

    // 封装前缀
    private String key(String conversationId) {
        return keyPrefix + conversationId;
    }
    /** 只处理三种「对话文本」消息；TOOL 返回 null，调用方跳过。 */
    private static String roleOf(MessageType type) {
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> null;
        };
    }

    // 根据不同角色 封装Message，避免ai不知道谁是谁
    private Message toMessage(StoredMessage stored) {
        if (stored == null || !StringUtils.hasText(stored.role()) || !StringUtils.hasText(stored.text())) {
            return null;
        }
        return switch (stored.role()) {
            case "user" -> new UserMessage(stored.text());
            case "assistant" -> new AssistantMessage(stored.text());
            case "system" -> new SystemMessage(stored.text());
            default -> null;
        };
    }

    /** 一条落库消息：只保留角色 + 文本，不碰 Spring AI 的内部字段。 */
    public record StoredMessage(String role, String text) {
    }

    /** 快照：版本 + 消息列表。 */
    public record MemorySnapshot(int version, List<StoredMessage> messages) {
    }
}
