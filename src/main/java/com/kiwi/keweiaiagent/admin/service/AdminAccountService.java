package com.kiwi.keweiaiagent.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.account.mapper.UserAccountMapper;
import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;
import com.kiwi.keweiaiagent.account.service.AccountRules;
import com.kiwi.keweiaiagent.admin.dto.AdminAccountPageResponse;
import com.kiwi.keweiaiagent.admin.dto.AdminAccountQuery;
import com.kiwi.keweiaiagent.admin.dto.AdminAccountResponse;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Console 账号管理服务。查询只返回页面所需字段；角色和状态修改会先按固定顺序锁定全部正常
 * admin，再锁定目标账号，并结合 version 检测并发冲突，确保系统始终保留正常 admin。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAccountService {

    private final UserAccountMapper userAccountMapper;

    /**
     * 按账号关键词、角色和状态组合查询账号，使用页码分页且不读取密码摘要等敏感字段。
     */
    public AdminAccountPageResponse listAccounts(Long operatorAccountId, AdminAccountQuery query) {
        int page = query.getPage();
        int size = query.getSize();
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "页码或每页数量不符合要求");
        }

        LambdaQueryWrapper<UserAccountDO> countWrapper = buildFilter(query);
        long total = userAccountMapper.selectCount(countWrapper);
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        long offset = (long) (page - 1) * size;
        if (total == 0 || offset >= total) {
            log.info("admin 查询账号列表，operatorAccountId={}，page={}，size={}，returned=0，total={}", operatorAccountId, page, size, total);
            return new AdminAccountPageResponse(List.of(), page, size, total, totalPages);
        }

        LambdaQueryWrapper<UserAccountDO> pageWrapper = buildFilter(query)
                .select(
                        UserAccountDO::getId,
                        UserAccountDO::getAccount,
                        UserAccountDO::getRole,
                        UserAccountDO::getStatus,
                        UserAccountDO::getRegisterTime,
                        UserAccountDO::getLastLoginTime,
                        UserAccountDO::getVersion
                )
                .orderByAsc(UserAccountDO::getId)
                .last("LIMIT " + size + " OFFSET " + offset);
        List<AdminAccountResponse> items = userAccountMapper.selectList(pageWrapper).stream()
                .map(account -> AdminAccountResponse.from(account, operatorAccountId))
                .toList();
        log.info(
                "admin 查询账号列表，operatorAccountId={}，page={}，size={}，returned={}，total={}",
                operatorAccountId,
                page,
                size,
                items.size(),
                total
        );
        return new AdminAccountPageResponse(items, page, size, total, totalPages);
    }

    /**
     * 调整其他账号角色。事务先锁定正常 admin 集合，再读取目标账号最新版本；降级正常 admin
     * 前重新检查集合数量，更新时继续使用页面 version 防止旧操作覆盖新数据。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public AdminAccountResponse updateRole(
            Long operatorAccountId,
            Long targetAccountId,
            AccountRole targetRole,
            Long expectedVersion
    ) {
        List<Long> activeAdminIds = userAccountMapper.lockActiveAdminIds();
        UserAccountDO target = userAccountMapper.selectByIdForUpdate(targetAccountId);
        if (target == null) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=role，result=ACCOUNT_NOT_FOUND", operatorAccountId, targetAccountId);
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        if (targetAccountId.equals(operatorAccountId)) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=role，before={}，after={}，result=SELF_MODIFICATION", operatorAccountId, targetAccountId, target.getRole(), targetRole);
            throw new BusinessException(ErrorCode.ADMIN_SELF_MODIFICATION);
        }
        if (!target.getVersion().equals(expectedVersion)) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=role，before={}，after={}，result=VERSION_CONFLICT", operatorAccountId, targetAccountId, target.getRole(), targetRole);
            throw new BusinessException(ErrorCode.ACCOUNT_UPDATE_CONFLICT);
        }
        if (target.getRole() == targetRole) {
            log.info("账号管理操作完成，operatorAccountId={}，targetAccountId={}，field=role，before={}，after={}，result=NO_CHANGE", operatorAccountId, targetAccountId, target.getRole(), targetRole);
            return AdminAccountResponse.from(target, operatorAccountId);
        }
        if (target.getRole() == AccountRole.ADMIN
                && target.getStatus() == AccountStatus.ACTIVE
                && targetRole == AccountRole.USER
                && activeAdminIds.size() <= 1) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=role，before={}，after={}，result=LAST_ACTIVE_ADMIN", operatorAccountId, targetAccountId, target.getRole(), targetRole);
            throw new BusinessException(ErrorCode.LAST_ACTIVE_ADMIN);
        }

        AccountRole previousRole = target.getRole();
        int updated = userAccountMapper.updateRoleWithVersion(targetAccountId, targetRole.name(), expectedVersion);
        if (updated != 1) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=role，before={}，after={}，result=VERSION_CONFLICT", operatorAccountId, targetAccountId, previousRole, targetRole);
            throw new BusinessException(ErrorCode.ACCOUNT_UPDATE_CONFLICT);
        }
        target.setRole(targetRole);
        target.setVersion(expectedVersion + 1);
        target.setUpdateTime(LocalDateTime.now());
        log.info("账号管理操作成功，operatorAccountId={}，targetAccountId={}，field=role，before={}，after={}，result=SUCCESS", operatorAccountId, targetAccountId, previousRole, targetRole);
        return AdminAccountResponse.from(target, operatorAccountId);
    }

    /**
     * 启用或停用目标账号。停用当前 admin 或最后一个正常 admin 会在事务内被拒绝；停用不会
     * 删除 Redis Session，目标账号现有会话继续有效，但退出后登录入口会按最新状态拒绝登录。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public AdminAccountResponse updateStatus(
            Long operatorAccountId,
            Long targetAccountId,
            AccountStatus targetStatus,
            Long expectedVersion
    ) {
        List<Long> activeAdminIds = userAccountMapper.lockActiveAdminIds();
        UserAccountDO target = userAccountMapper.selectByIdForUpdate(targetAccountId);
        if (target == null) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=status，result=ACCOUNT_NOT_FOUND", operatorAccountId, targetAccountId);
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        if (targetAccountId.equals(operatorAccountId) && targetStatus == AccountStatus.DISABLED) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=status，before={}，after={}，result=SELF_MODIFICATION", operatorAccountId, targetAccountId, target.getStatus(), targetStatus);
            throw new BusinessException(ErrorCode.ADMIN_SELF_MODIFICATION);
        }
        if (!target.getVersion().equals(expectedVersion)) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=status，before={}，after={}，result=VERSION_CONFLICT", operatorAccountId, targetAccountId, target.getStatus(), targetStatus);
            throw new BusinessException(ErrorCode.ACCOUNT_UPDATE_CONFLICT);
        }
        if (target.getStatus() == targetStatus) {
            log.info("账号管理操作完成，operatorAccountId={}，targetAccountId={}，field=status，before={}，after={}，result=NO_CHANGE", operatorAccountId, targetAccountId, target.getStatus(), targetStatus);
            return AdminAccountResponse.from(target, operatorAccountId);
        }
        if (target.getRole() == AccountRole.ADMIN
                && target.getStatus() == AccountStatus.ACTIVE
                && targetStatus == AccountStatus.DISABLED
                && activeAdminIds.size() <= 1) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=status，before={}，after={}，result=LAST_ACTIVE_ADMIN", operatorAccountId, targetAccountId, target.getStatus(), targetStatus);
            throw new BusinessException(ErrorCode.LAST_ACTIVE_ADMIN);
        }

        AccountStatus previousStatus = target.getStatus();
        int updated = userAccountMapper.updateStatusWithVersion(targetAccountId, targetStatus.name(), expectedVersion);
        if (updated != 1) {
            log.warn("账号管理操作失败，operatorAccountId={}，targetAccountId={}，field=status，before={}，after={}，result=VERSION_CONFLICT", operatorAccountId, targetAccountId, previousStatus, targetStatus);
            throw new BusinessException(ErrorCode.ACCOUNT_UPDATE_CONFLICT);
        }
        target.setStatus(targetStatus);
        target.setVersion(expectedVersion + 1);
        target.setUpdateTime(LocalDateTime.now());
        log.info("账号管理操作成功，operatorAccountId={}，targetAccountId={}，field=status，before={}，after={}，result=SUCCESS", operatorAccountId, targetAccountId, previousStatus, targetStatus);
        return AdminAccountResponse.from(target, operatorAccountId);
    }

    /**
     * 构造账号列表过滤条件。关键词先按登录规则去除首尾空格并转为小写，再通过 LOCATE 执行
     * 字面量子串搜索，避免账号中的下划线被 SQL LIKE 误当作单字符通配符。
     */
    private LambdaQueryWrapper<UserAccountDO> buildFilter(AdminAccountQuery query) {
        String keyword = AccountRules.normalizeForLogin(query.getKeyword());
        return new LambdaQueryWrapper<UserAccountDO>()
                .apply(StringUtils.hasText(keyword), "LOCATE({0}, normalized_account) > 0", keyword)
                .eq(query.getRole() != null, UserAccountDO::getRole, query.getRole())
                .eq(query.getStatus() != null, UserAccountDO::getStatus, query.getStatus());
    }
}
