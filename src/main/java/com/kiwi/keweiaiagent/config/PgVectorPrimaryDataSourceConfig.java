package com.kiwi.keweiaiagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVectorStore 使用的主 PostgreSQL 数据源配置。
 *
 * <p>聊天记忆和账号业务通过限定名称继续使用独立 MySQL 数据源，{@code @Primary}
 * 仅解决 PgVector 自动配置按类型注入 DataSource/JdbcTemplate 时的选择问题。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PgVectorPrimaryDataSourceConfig {

    /**
     * 绑定 {@code spring.datasource} 下的 PostgreSQL 连接属性。
     *
     * @return PostgreSQL 数据源属性
     */
    @Bean(name = "dataSourceProperties")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 使用标准 DataSourceProperties 创建 PostgreSQL 连接池。
     *
     * @param properties PostgreSQL 数据源属性
     * @return PgVector 使用的主数据源
     */
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("dataSourceProperties") DataSourceProperties properties) {
        // 通过 DataSourceProperties 创建，确保 url 能正确映射到 Hikari 的 jdbcUrl。
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 创建 PgVector 自动配置默认注入的主 JdbcTemplate。
     *
     * @param dataSource PostgreSQL 主数据源
     * @return PgVector JDBC 模板
     */
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
