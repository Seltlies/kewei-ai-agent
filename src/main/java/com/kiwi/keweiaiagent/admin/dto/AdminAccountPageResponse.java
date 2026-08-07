package com.kiwi.keweiaiagent.admin.dto;

import java.util.List;

/**
 * Console 账号页码分页响应，返回当前页、每页数量、总记录数和总页数，便于前端在搜索或筛选
 * 变化后准确回到第 1 页并控制上一页、下一页按钮。
 */
public record AdminAccountPageResponse(
        List<AdminAccountResponse> items,
        int page,
        int size,
        long total,
        long totalPages
) {
}
