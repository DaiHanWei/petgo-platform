package com.tailtopia.analytics.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcProperties;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 埋点库（第二数据源）与业务库的显式装配（analytics-pipeline Story 1.1，架构「启动隔离」）。
 *
 * <h2>🔴 为什么业务侧 bean 要在这里重新定义一遍</h2>
 * 一旦容器里出现第二个 {@link DataSource} / {@link JdbcTemplate} / {@link TransactionTemplate}，
 * Spring Boot 4.0.6 的以下自动配置会<b>退让</b>，必须在这里显式补齐，且 bean 名与 Boot 的一致：
 * <ul>
 *   <li>{@code DataSourceAutoConfiguration} 连接池（{@code @ConditionalOnMissingBean(DataSource)}）→ {@link #dataSource}；
 *       仍绑定 {@code spring.datasource.hikari.*}（{@code ApiIntegrationTest} 靠它把池压到 3）。</li>
 *   <li>{@code JdbcTemplateConfiguration}（{@code @ConditionalOnMissingBean(JdbcOperations)}）→ {@link #jdbcTemplate}，
 *       同样套用 {@code spring.jdbc.template.*}。</li>
 *   <li>{@code NamedParameterJdbcTemplateConfiguration}（依赖单一 {@code JdbcTemplate} 候选）→ {@link #namedParameterJdbcTemplate}。</li>
 *   <li>{@code TransactionAutoConfiguration.TransactionTemplateConfiguration}
 *       （{@code @ConditionalOnMissingBean(TransactionOperations)}）→ {@link #transactionTemplate}。
 *       story 清单未列这一项，实现时用 javap 核实：埋点 {@code TransactionTemplate} 注册成 bean 后它也会退让，
 *       而 {@code AiQueryCleanup} / {@code GrowthRecalcService} 按类型注入 {@code TransactionTemplate}。</li>
 *   <li>{@code DataSourceHealthContributorAutoConfiguration} 会把所有数据源并进 {@code db} 健康项 →
 *       {@link #dbHealthContributor} 只包业务库（埋点库挂了 {@code /actuator/health} 仍 UP，部署脚本靠它判活）。</li>
 * </ul>
 *
 * <h2>埋点侧</h2>
 * 全部 {@code @Qualifier("analytics")} + {@code defaultCandidate = false}：只有显式带限定符的注入点能拿到，
 * 按类型注入永远落到业务库。
 * <ul>
 *   <li>埋点的 {@link DataSourceTransactionManager} <b>不注册成 bean</b>：存在任意 {@code TransactionManager} bean
 *       时 JPA 的 {@code transactionManager} 自动配置会退让，业务 {@code @Transactional} 全线失效。只暴露
 *       {@link #analyticsTransactionTemplate}。</li>
 *   <li>埋点 Flyway 同理<b>不注册成 bean</b>（{@code FlywayAutoConfiguration} 对 {@code Flyway} 类型退让 = 业务迁移不跑），
 *       由 {@link AnalyticsBootstrap} 以普通对象运行。</li>
 *   <li>连接池 {@code initialization-fail-timeout=-1}：建池不连库，库不在时启动不抛；连不上由 Bootstrap 判停用。</li>
 * </ul>
 *
 * <p>埋点表一律走 {@code JdbcTemplate}，禁止 JPA 实体（架构 §实现规范 5）。
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsDataSourceConfig {

    /** 埋点侧 bean 的统一限定符。 */
    public static final String QUALIFIER = "analytics";

    // ---------------------------------------------------------------- 业务库（@Primary，保持原行为）

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource, ObjectProvider<JdbcProperties> jdbcProperties,
            ObjectProvider<SQLExceptionTranslator> sqlExceptionTranslator) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        JdbcProperties props = jdbcProperties.getIfAvailable();
        if (props != null) {
            JdbcProperties.Template t = props.getTemplate();
            template.setIgnoreWarnings(t.isIgnoreWarnings());
            template.setFetchSize(t.getFetchSize());
            template.setMaxRows(t.getMaxRows());
            if (t.getQueryTimeout() != null) {
                template.setQueryTimeout((int) t.getQueryTimeout().getSeconds());
            }
            template.setSkipResultsProcessing(t.isSkipResultsProcessing());
            template.setSkipUndeclaredResults(t.isSkipUndeclaredResults());
            template.setResultsMapCaseInsensitive(t.isResultsMapCaseInsensitive());
        }
        // 与 Boot 4.0.6 JdbcTemplateConfiguration 一致：容器里有唯一 SQLExceptionTranslator bean 时套用（复审补齐）
        sqlExceptionTranslator.ifUnique(template::setExceptionTranslator);
        return template;
    }

    @Bean
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** 业务库事务模板（等价于 Boot 自动配置的 {@code transactionTemplate}，绑定 JPA 的事务管理器）。 */
    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * {@code db} 健康项只看业务库。Boot 4 的 {@code DataSourceHealthContributorAutoConfiguration} 对名字
     * {@code dbHealthIndicator} / {@code dbHealthContributor} 是 {@code @ConditionalOnMissingBean}，见名退让。
     */
    @Bean
    @ConditionalOnEnabledHealthIndicator("db")
    public HealthIndicator dbHealthContributor(DataSource dataSource) {
        return new DataSourceHealthIndicator(dataSource);
    }

    // ---------------------------------------------------------------- 埋点库（@Qualifier("analytics")）

    @Bean(defaultCandidate = false)
    @Qualifier(QUALIFIER)
    @ConfigurationProperties("analytics.datasource.hikari")
    public HikariDataSource analyticsDataSource(
            @Value("${analytics.datasource.url}") String url,
            @Value("${analytics.datasource.username}") String username,
            @Value("${analytics.datasource.password}") String password) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier(QUALIFIER)
    public JdbcTemplate analyticsJdbcTemplate(@Qualifier(QUALIFIER) DataSource analyticsDataSource) {
        return new JdbcTemplate(analyticsDataSource);
    }

    /** 埋点库事务模板。内部的 {@link DataSourceTransactionManager} 刻意不注册成 bean（见类注释）。 */
    @Bean(defaultCandidate = false)
    @Qualifier(QUALIFIER)
    public TransactionTemplate analyticsTransactionTemplate(@Qualifier(QUALIFIER) DataSource analyticsDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(analyticsDataSource));
    }
}
