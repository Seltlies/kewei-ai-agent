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

@Configuration
@ConditionalOnProperty(prefix = "app.datasource.mysql", name = "url")
@MapperScan(
        basePackages = {
                "com.kiwi.keweiaiagent.chatmemory.mapper",
                "com.kiwi.keweiaiagent.account.mapper"
        },
        sqlSessionFactoryRef = "chatMemorySqlSessionFactory"
)
public class ChatMemoryMySqlDataSourceConfig {

    @Bean(name = "chatMemoryMySqlDataSourceProperties")
    @ConfigurationProperties(prefix = "app.datasource.mysql")
    public DataSourceProperties chatMemoryMySqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "chatMemoryMySqlDataSource")
    public DataSource chatMemoryMySqlDataSource(
            @Qualifier("chatMemoryMySqlDataSourceProperties") DataSourceProperties properties
    ) {
        // 使用 DataSourceProperties 创建数据源，确保 url 能正确映射为 Hikari 的 jdbcUrl。
        return properties.initializeDataSourceBuilder().build();
    }

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

    @Bean(name = "chatMemorySqlSessionFactory")
    public SqlSessionFactory chatMemorySqlSessionFactory(
            @Qualifier("chatMemoryMySqlDataSource") DataSource dataSource
    ) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }

    @Bean(name = "chatMemorySqlSessionTemplate")
    public SqlSessionTemplate chatMemorySqlSessionTemplate(
            @Qualifier("chatMemorySqlSessionFactory") SqlSessionFactory sqlSessionFactory
    ) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
