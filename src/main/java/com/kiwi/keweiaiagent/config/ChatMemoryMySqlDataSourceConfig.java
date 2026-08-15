package com.kiwi.keweiaiagent.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * MySQL 业务数据源及 MyBatis 基础设施配置。
 *
 * <p>项目同时使用 PostgreSQL/PgVector 与 MySQL，因此这里为数据源、事务管理器、
 * SqlSessionFactory 和 JdbcTemplate 设置独立名称，并让账号、会话、附件、Agent 执行记录
 * 的 Mapper 明确绑定 MySQL。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.datasource.mysql", name = "url")
@MapperScan(
        basePackages = {
                "com.kiwi.keweiaiagent.chatmemory.mapper",
                "com.kiwi.keweiaiagent.account.mapper",
                "com.kiwi.keweiaiagent.chat.mapper",
                "com.kiwi.keweiaiagent.agent.mapper"
        },
        sqlSessionFactoryRef = "chatMemorySqlSessionFactory"
)
public class ChatMemoryMySqlDataSourceConfig {

    /**
     * 将 {@code app.datasource.mysql} 外部配置绑定为标准数据源属性。
     *
     * @return MySQL 数据源属性
     */
    @Bean(name = "chatMemoryMySqlDataSourceProperties")
    @ConfigurationProperties(prefix = "app.datasource.mysql")
    public DataSourceProperties chatMemoryMySqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 根据标准属性创建 MySQL 连接池数据源。
     *
     * @param properties 已完成外部配置绑定的数据源属性
     * @return MySQL 数据源
     */
    @Bean(name = "chatMemoryMySqlDataSource")
    public DataSource chatMemoryMySqlDataSource(
            @Qualifier("chatMemoryMySqlDataSourceProperties") DataSourceProperties properties
    ) {
        // 使用 DataSourceProperties 创建数据源，确保 url 能正确映射为 Hikari 的 jdbcUrl。
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 创建绑定 MySQL 的 JdbcTemplate，供表结构初始化等直接 SQL 场景使用。
     *
     * @param dataSource MySQL 数据源
     * @return MySQL JdbcTemplate
     */
    @Bean(name = "mysqlChatMemoryJdbcTemplate")
    public JdbcTemplate mysqlChatMemoryJdbcTemplate(
            @Qualifier("chatMemoryMySqlDataSource") DataSource dataSource
    ) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 为账号和聊天业务提供明确绑定 MySQL 数据源的事务管理器，避免多数据源环境下误用
     * PgVector 对应的主数据源事务。账号注册、最后登录时间更新和后续管理操作均通过该
     * 事务管理器提交。
     */
    @Bean(name = "mysqlTransactionManager")
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("chatMemoryMySqlDataSource") DataSource dataSource
    ) {
        return new JdbcTransactionManager(dataSource);
    }

    /**
     * 创建 MyBatis-Plus 使用的 MySQL SqlSessionFactory。
     *
     * @param dataSource MySQL 数据源
     * @return 绑定 MySQL 的会话工厂
     * @throws Exception 工厂初始化失败时抛出
     */
    @Bean(name = "chatMemorySqlSessionFactory")
    public SqlSessionFactory chatMemorySqlSessionFactory(
            @Qualifier("chatMemoryMySqlDataSource") DataSource dataSource
    ) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }

    /**
     * 创建线程安全的 MyBatis SqlSessionTemplate，负责会话生命周期和异常转换。
     *
     * @param sqlSessionFactory MySQL 会话工厂
     * @return MySQL SqlSessionTemplate
     */
    @Bean(name = "chatMemorySqlSessionTemplate")
    public SqlSessionTemplate chatMemorySqlSessionTemplate(
            @Qualifier("chatMemorySqlSessionFactory") SqlSessionFactory sqlSessionFactory
    ) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
