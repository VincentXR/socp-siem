package com.socp.platform.error.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 统一分页响应体。全平台列表端点共用它，与 {@link ApiResult} 搭配使用：
 * {@code ApiResult.ok(PageResponse.of(items, total, page, size))}。
 *
 * <p>契约（与 alert-web 历史分页形状字段一致，序列化兼容）：
 * <ul>
 *   <li>页码 1-based：{@code page} 从 1 起，0 或负数直接拒绝；</li>
 *   <li>字段名固定为 {@code items / total / page / size / totalPages}；</li>
 *   <li>{@code totalPages} 由工厂按 {@code ceil(total/size)} 计算，size 无效时省略（NON_NULL）；</li>
 *   <li>类型不可变：构造时防御性拷贝 items，禁止外部修改。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int size,
        Integer totalPages
) {
    public PageResponse {
        if (page < 1) {
            throw new IllegalArgumentException("page is 1-based, got: " + page);
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0, got: " + size);
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must be >= 0, got: " + total);
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size, totalPagesOf(total, size));
    }

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size, Integer totalPages) {
        return new PageResponse<>(items, total, page, size, totalPages);
    }

    private static Integer totalPagesOf(long total, int size) {
        return size <= 0 ? null : (int) ((total + size - 1) / size);
    }
}
