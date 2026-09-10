package com.rj.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页返回结构,避免把 MyBatis-Plus 的 IPage 直接暴露成接口契约。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private long current;

    /** 每页条数 */
    private long size;
}
