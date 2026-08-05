package com.kiwi.keweiaiagent.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import com.kiwi.keweiaiagent.account.mapper.UserAccountMapper;
import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;
import com.kiwi.keweiaiagent.exception.BusinessException;
import com.kiwi.keweiaiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户账号领域服务，负责注册、凭据校验、账号读取和初始 admin 创建。该服务只操作 MySQL
 * 账号数据，不直接创建 HttpSession，认证会话由 AuthSessionService 统一管理。
 */
@Service
@Slf4j
public class UserAccountService {

    private static final String BOOTSTRAP_ADMIN_LOCK = "BOOTSTRAP_ADMIN";

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate mysqlJdbcTemplate;
    private final String dummyPasswordHash;

    public UserAccountService(
            UserAccountMapper userAccountMapper,
            PasswordEncoder passwordEncoder,
            @Qualifier("mysqlChatMemoryJdbcTemplate") JdbcTemplate mysqlJdbcTemplate
    ) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        // 不存在账号时也执行一次 BCrypt 校验，降低通过响应耗时判断账号是否存在的风险。
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 创建公开注册账号。账号规则、密码规则、确认密码和数据库唯一索引会共同阻止非法或
     * 并发重复注册，新账号固定为 USER、ACTIVE。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public UserAccountDO registerUser(String rawAccount, String password, String confirmPassword) {
        AccountRules.AccountValue accountValue = AccountRules.validateAccount(rawAccount);
        AccountRules.validateUserPassword(password);
        if (!password.equals(confirmPassword)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "两次输入的密码不一致");
        }
        if (findByNormalizedAccount(accountValue.normalizedAccount()) != null) {
            throw new BusinessException(ErrorCode.ACCOUNT_EXISTS);
        }

        UserAccountDO account = buildAccount(
                accountValue,
                passwordEncoder.encode(password),
                AccountRole.USER
        );
        try {
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            log.warn("账号并发注册冲突，normalizedAccount={}", accountValue.normalizedAccount());
            throw new BusinessException(ErrorCode.ACCOUNT_EXISTS);
        }
        log.info("账号注册成功，accountId={}，role={}", account.getId(), account.getRole());
        return account;
    }

    /**
     * 校验登录凭据并更新最后登录时间。错误账号与错误密码使用同一错误码和提示；只有账号
     * 存在、密码正确且状态为停用时，才返回明确的停用提示。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public UserAccountDO authenticate(String rawAccount, String password) {
        String normalizedAccount = AccountRules.normalizeForLogin(rawAccount);
        UserAccountDO account = StringUtils.hasText(normalizedAccount)
                ? findByNormalizedAccount(normalizedAccount)
                : null;
        String passwordToCheck = password == null ? "" : password;
        // BCrypt 最多接收 72 个字节；登录入口先按同一上限拒绝超长输入，防止异常穿透为 500。
        boolean passwordLengthAllowed = passwordToCheck.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean passwordMatches = passwordLengthAllowed && (account == null
                ? passwordEncoder.matches(passwordToCheck, dummyPasswordHash)
                : passwordEncoder.matches(passwordToCheck, account.getPasswordHash()));

        if (account == null || !passwordMatches) {
            log.warn("账号登录失败，原因=凭据不匹配");
            throw new BusinessException(ErrorCode.ACCOUNT_OR_PASSWORD_ERROR);
        }
        if (account.getStatus() == AccountStatus.DISABLED) {
            log.warn("停用账号尝试登录，accountId={}", account.getId());
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        LocalDateTime loginTime = LocalDateTime.now();
        userAccountMapper.update(
                null,
                new LambdaUpdateWrapper<UserAccountDO>()
                        .eq(UserAccountDO::getId, account.getId())
                        .set(UserAccountDO::getLastLoginTime, loginTime)
        );
        account.setLastLoginTime(loginTime);
        log.info("账号登录校验成功，accountId={}，role={}", account.getId(), account.getRole());
        return account;
    }

    /**
     * 按主键读取账号。安全过滤器每次请求都会调用该方法取得最新角色，确保角色调整立即生效。
     */
    public UserAccountDO findById(Long accountId) {
        return accountId == null ? null : userAccountMapper.selectById(accountId);
    }

    /**
     * 在账号表为空时创建初始 admin；表非空时只校验冲突和可用 admin，不修改任何既有账号。
     */
    @Transactional(transactionManager = "mysqlTransactionManager")
    public BootstrapAdminResult initializeBootstrapAdmin(String rawAccount, String password) {
        AccountRules.AccountValue accountValue = AccountRules.validateAccount(rawAccount);
        AccountRules.validateBootstrapAdminPassword(password);

        // 锁定固定数据库记录，使多个应用实例在同一事务内串行完成“判空并创建”操作。
        String acquiredLock = mysqlJdbcTemplate.queryForObject(
                "SELECT lock_name FROM ai_system_lock WHERE lock_name = ? FOR UPDATE",
                String.class,
                BOOTSTRAP_ADMIN_LOCK
        );
        if (!BOOTSTRAP_ADMIN_LOCK.equals(acquiredLock)) {
            throw new BusinessException(
                    ErrorCode.BOOTSTRAP_ADMIN_INVALID,
                    "初始 admin 初始化锁不存在，需要先执行账号体系数据库脚本"
            );
        }
        log.info("已取得初始 admin 数据库初始化锁，开始检查账号表状态");

        long accountCount = userAccountMapper.selectCount(null);
        if (accountCount > 0) {
            UserAccountDO configuredAccount = findByNormalizedAccount(accountValue.normalizedAccount());
            if (configuredAccount != null
                    && (configuredAccount.getRole() != AccountRole.ADMIN
                    || configuredAccount.getStatus() != AccountStatus.ACTIVE)) {
                throw new BusinessException(
                        ErrorCode.BOOTSTRAP_ADMIN_INVALID,
                        "初始 admin 配置账号与既有非正常 admin 账号冲突，需要人工处理"
                );
            }
            if (countActiveAdmins() == 0) {
                throw new BusinessException(
                        ErrorCode.BOOTSTRAP_ADMIN_INVALID,
                        "账号表非空但不存在正常状态 admin，需要人工处理"
                );
            }
            return BootstrapAdminResult.SKIPPED_EXISTING_DATA;
        }

        UserAccountDO admin = buildAccount(
                accountValue,
                passwordEncoder.encode(password),
                AccountRole.ADMIN
        );
        try {
            userAccountMapper.insert(admin);
            log.info("初始 admin 创建成功，accountId={}，account={}", admin.getId(), admin.getAccount());
            return BootstrapAdminResult.CREATED;
        } catch (DuplicateKeyException e) {
            UserAccountDO concurrentAccount = findByNormalizedAccount(accountValue.normalizedAccount());
            if (concurrentAccount != null
                    && concurrentAccount.getRole() == AccountRole.ADMIN
                    && concurrentAccount.getStatus() == AccountStatus.ACTIVE) {
                log.info("初始 admin 已由并发实例创建，accountId={}", concurrentAccount.getId());
                return BootstrapAdminResult.SKIPPED_EXISTING_DATA;
            }
            throw new BusinessException(
                    ErrorCode.BOOTSTRAP_ADMIN_INVALID,
                    "初始 admin 并发初始化冲突，需要人工处理",
                    e
            );
        }
    }

    private UserAccountDO findByNormalizedAccount(String normalizedAccount) {
        return userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccountDO>()
                        .eq(UserAccountDO::getNormalizedAccount, normalizedAccount)
                        .last("LIMIT 1")
        );
    }

    private long countActiveAdmins() {
        return userAccountMapper.selectCount(
                new LambdaQueryWrapper<UserAccountDO>()
                        .eq(UserAccountDO::getRole, AccountRole.ADMIN)
                        .eq(UserAccountDO::getStatus, AccountStatus.ACTIVE)
        );
    }

    private UserAccountDO buildAccount(
            AccountRules.AccountValue accountValue,
            String passwordHash,
            AccountRole role
    ) {
        LocalDateTime now = LocalDateTime.now();
        UserAccountDO account = new UserAccountDO();
        account.setAccount(accountValue.displayAccount());
        account.setNormalizedAccount(accountValue.normalizedAccount());
        account.setPasswordHash(passwordHash);
        account.setRole(role);
        account.setStatus(AccountStatus.ACTIVE);
        account.setRegisterTime(now);
        account.setCreateTime(now);
        account.setUpdateTime(now);
        account.setVersion(0L);
        return account;
    }

    /**
     * 初始 admin 初始化结果，用于区分实际创建和因既有合法数据而跳过。
     */
    public enum BootstrapAdminResult {
        CREATED,
        SKIPPED_EXISTING_DATA
    }
}
