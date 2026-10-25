package com.agentworkorder.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis value 的统一 JSON 编解码。
 *
 * <p>为什么单独抽一个类：三个 Redis 存储都要「序列化 / 反序列化 + 统一异常」，
 * 如果每个类各写一遍 try/catch，很容易漏掉某一处，最后变成「有的地方吞异常、有的地方抛 500」。
 *
 * <p>注意：这里用 Spring Boot 自动配置的 Jackson 2 ObjectMapper，
 * 不要自己 new 一个（会丢掉 Boot 注册的模块，比如 JavaTimeModule）。
 */
@Component
public class JsonCodec {
    // jackson序列化 对象
    private final ObjectMapper objectMapper;

    // SpringBoot启动扫描时 自动注入 ObjectMapper
    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对象 -> JSON 序列化
     * <p>
     * 调用 writeValueAsString()，支持字节传参和object传参
     * </p>
    */
    public  String toJson(Object object) {
        try{
            // 调用  writeValueAsString,Java 对象序列化为 JSON 字符串
            return objectMapper.writeValueAsString(object);
        }catch(JsonProcessingException e){
            // 如果失败了 按照要求降级
            throw new IllegalStateException("JSON 序列化失败");
        }
    }

    /**
     * JSON -> 对象 反序列化
     * <p>
     * 调用 readValue(String,type),type是指定对象的类型
     * </p>
     */
    public <T> T fromJson(String json, Class<T> type) {
        try{
            return  objectMapper.readValue(json, type);
        }catch (JsonProcessingException e){
            throw new IllegalStateException("JSON 反序列化失败");
        }
    }

    /**
     * 将JSON字符串反序列化为List<T>集合
     * 借助TypeReference解决Java泛型擦除问题，让Jackson可以识别集合内部元素真实类型
     * @param json JSON字符串
     * @param type 泛型类型引用，示例：new TypeReference<List<UserDTO>>(){}
     */
    public <T> List<T> fromJsonList(String json, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
