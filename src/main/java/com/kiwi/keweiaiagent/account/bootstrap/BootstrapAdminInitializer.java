package com.kiwi.keweiaiagent.account.bootstrap;

import com.kiwi.keweiaiagent.account.service.UserAccountService;
import com.kiwi.keweiaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 初始 admin 启动初始化器。真实账号和密码只通过三个固定环境变量读取，不使用 Nacos、
 * application.yml 默认值或代码常量。账号表存在数据时绝不提权或覆盖密码。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapAdminInitializer implements SmartInitializingSingleton {

    private final UserAccountService userAccountService;

    /**
     * 在全部普通单例创建完成、Web Server 正式开始接收请求之前执行幂等初始化。这样公开注册
     * 不会抢先写入空账号表；初始化关闭时不访问账号表，配置错误时直接终止上下文刷新。
     */
    @Override
    public void afterSingletonsInstantiated() {
        boolean enabled = Boolean.parseBoolean(System.getenv("BOOTSTRAP_ADMIN_ENABLED"));
        if (!enabled) {
            log.info("初始 admin 自动创建未启用");
            return;
        }

        String account = System.getenv("BOOTSTRAP_ADMIN_ACCOUNT");
        String password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");
        if (!StringUtils.hasText(account) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("已启用初始 admin，但账号或密码环境变量未配置");
        }

        try {
            UserAccountService.BootstrapAdminResult result =
                    userAccountService.initializeBootstrapAdmin(account, password);
            log.info("初始 admin 初始化完成，result={}，account={}", result, account.strip());
        } catch (BusinessException e) {
            log.error("初始 admin 初始化失败，原因={}", e.getMessage());
            throw new IllegalStateException("初始 admin 初始化失败，需要人工处理", e);
        }
    }
}
