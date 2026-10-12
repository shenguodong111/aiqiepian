package com.aiqiepian.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页结构：{ list, total, page, size }（与 mock-server 一致，默认 page=1 size=20）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private long total;
    private int page;
    private int size;

    /** 内存分页，语义与 mock-server 的 page(list, q) 完全一致。 */
    public static <T> PageResult<T> of(List<T> all, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : size;
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), p, s);
    }
}
