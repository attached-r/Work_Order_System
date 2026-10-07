package com.rj.cache;

import lombok.Data;

/**
 * 逻辑过期包装(模块四缓存击穿防护)。
 * <p>
 * 把「业务过期时间」与「Redis 物理过期时间」解耦:值写入时记录一个逻辑过期时间点
 * {@code expireAtEpochMilli},物理 TTL 则设得更长(见 application.yaml)。
 * 读缓存时:
 * <ul>
 *   <li>{@code now < expireAtEpochMilli} → 直接返回 {@code data};</li>
 *   <li>{@code now >= expireAtEpochMilli} → <b>立即返回旧值</b>,同时提交一次异步重建。</li>
 * </ul>
 * 好处是「超热键逻辑过期的那一刻,读请求永不阻塞」;代价是返回的那一次可能是旧值,
 * 陈旧窗口 = 异步重建耗时(通常毫秒级),业务可接受(详情页非强一致场景)。
 * <p>
 * 注意:本类由既有的 {@code RedisTemplate}(Jackson3 + default typing)序列化,
 * JSON 里会带 {@code @class},因此 {@code data} 的真实类型在反序列化时能还原。
 *
 * @param <T> 被包装的业务数据类型,如 {@code WorkOrderDetailVO}
 */
@Data
public class RedisData<T> {

    /** 业务数据本体 */
    private T data;

    /** 逻辑过期时间点(Unix 毫秒)。到达该时刻后本次读返回旧值并触发异步重建 */
    private long expireAtEpochMilli;
}
