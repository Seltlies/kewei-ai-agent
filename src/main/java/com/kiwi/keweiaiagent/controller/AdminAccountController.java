package com.kiwi.keweiaiagent.controller;

import com.kiwi.keweiaiagent.admin.dto.AdminAccountPageResponse;
import com.kiwi.keweiaiagent.admin.dto.AdminAccountQuery;
import com.kiwi.keweiaiagent.admin.dto.AdminAccountResponse;
import com.kiwi.keweiaiagent.admin.dto.UpdateAccountRoleRequest;
import com.kiwi.keweiaiagent.admin.dto.UpdateAccountStatusRequest;
import com.kiwi.keweiaiagent.admin.service.AdminAccountService;
import com.kiwi.keweiaiagent.security.AuthenticatedAccount;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console 账号管理 REST 接口。`/admin/**` 已由 Spring Security 限定为 admin；控制器只从
 * 认证主体取得操作者账号 ID，不接受前端声明操作者、角色或“当前账号”标志。
 */
@RestController
@RequestMapping("/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    /**
     * 按关键词、角色、状态和页码组合查询账号，响应不包含密码与聊天内容。
     */
    @GetMapping
    public AdminAccountPageResponse listAccounts(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid AdminAccountQuery query
    ) {
        return adminAccountService.listAccounts(account.accountId(), query);
    }

    /**
     * 调整其他账号角色，version 用于检测列表加载后的并发变化。
     */
    @PatchMapping("/{accountId}/role")
    public AdminAccountResponse updateRole(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateAccountRoleRequest request
    ) {
        return adminAccountService.updateRole(account.accountId(), accountId, request.role(), request.version());
    }

    /**
     * 启用或停用目标账号；停用不清除目标账号已经存在的 Redis Session。
     */
    @PatchMapping("/{accountId}/status")
    public AdminAccountResponse updateStatus(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateAccountStatusRequest request
    ) {
        return adminAccountService.updateStatus(account.accountId(), accountId, request.status(), request.version());
    }
}
