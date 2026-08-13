package com.kiwi.keweiaiagent.config;

import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.configdata.NacosConfigDataLocationResolver;
import com.alibaba.cloud.nacos.configdata.NacosConfigDataResource;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.logging.DeferredLogFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 在 Spring Boot Config Data 阶段解析由本机 {@code .env} 提供的 Nacos Data ID。
 *
 * <p>Spring Cloud Alibaba 的官方 {@link NacosConfigDataLocationResolver} 会直接把
 * {@code nacos:} 后面的文本拼接为 URI，不会展开其中的 Spring 占位符。本解析器通过
 * Config Data SPI 提前执行，调用父类的 {@code loadProperties} 从已经导入的本机
 * Environment 中取得 {@code spring.cloud.nacos.config.name}，再把确定的 Data ID
 * 交回官方解析器完成鉴权、配置读取和失败处理。数据库与 Redis 凭据不会经过本类，
 * 也不会写入日志。</p>
 */
public final class NacosEnvironmentConfigDataLocationResolver extends NacosConfigDataLocationResolver {

    private static final String DATA_ID_PLACEHOLDER = "${NACOS_DATA_ID}";

    /**
     * Data ID 作为 Nacos URI 的单个路径段使用，只允许固定命名所需的安全字符，
     * 防止本机配置中的斜杠或查询串改变目标配置的语义。
     */
    private static final Pattern SAFE_DATA_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * 使用 Spring Boot 提供的延迟日志工厂，确保 Config Data 初始化期的日志在
     * 日志系统就绪后再输出；父类仍负责创建并管理官方 Nacos Config 客户端。
     *
     * @param logFactory Spring Boot Config Data 阶段提供的延迟日志工厂
     */
    public NacosEnvironmentConfigDataLocationResolver(DeferredLogFactory logFactory) {
        super(logFactory);
    }

    /**
     * 比官方解析器优先一级，使带 {@code NACOS_DATA_ID} 占位符的导入地址先由本类处理。
     * 字面量 Nacos 地址仍直接委托父类，保持官方解析行为不变。
     *
     * @return Config Data 解析器顺序
     */
    @Override
    public int getOrder() {
        return super.getOrder() - 1;
    }

    /**
     * 把导入地址中的固定 Data ID 占位符替换为本机 Environment 已解析的值，然后调用
     * {@link NacosConfigDataLocationResolver#resolveProfileSpecific} 加载目标配置。该方法
     * 不提供默认 Data ID；缺失或格式非法都会直接终止应用启动。
     *
     * @param resolverContext Config Data 解析上下文，可读取当前 Binder 与引导上下文
     * @param location application.yml 声明的 Nacos 导入地址
     * @param profiles 当前激活的 Spring Profiles
     * @return 官方解析器生成的 Nacos 配置资源
     * @throws ConfigDataLocationNotFoundException Nacos 配置地址不存在时由父类抛出
     */
    @Override
    public List<NacosConfigDataResource> resolveProfileSpecific(
            ConfigDataLocationResolverContext resolverContext,
            ConfigDataLocation location,
            Profiles profiles
    ) throws ConfigDataLocationNotFoundException {
        String configuredDataId = location.getNonPrefixedValue(getPrefix());
        if (!DATA_ID_PLACEHOLDER.equals(configuredDataId)) {
            return super.resolveProfileSpecific(resolverContext, location, profiles);
        }

        NacosConfigProperties properties = loadProperties(resolverContext);
        String dataId = properties.getName();
        if (dataId == null || !SAFE_DATA_ID.matcher(dataId).matches()) {
            throw new IllegalStateException("NACOS_DATA_ID 缺失或格式非法，应用拒绝加载 Nacos 配置");
        }

        String optionalPrefix = location.isOptional() ? ConfigDataLocation.OPTIONAL_PREFIX : "";
        ConfigDataLocation resolvedLocation = ConfigDataLocation.of(optionalPrefix + getPrefix() + dataId);
        getLog().info("已从本机引导配置解析 Nacos Data ID，开始加载远程配置");
        return super.resolveProfileSpecific(resolverContext, resolvedLocation, profiles);
    }
}
