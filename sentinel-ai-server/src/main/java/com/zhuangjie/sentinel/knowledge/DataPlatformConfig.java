package com.zhuangjie.sentinel.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the remote data query platform used to collect
 * production DDL metadata when direct JDBC access is unavailable.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sentinel.data-platform")
public class DataPlatformConfig {

    private boolean enabled = false;

    /** SSO authentication base URL */
    private String ssoUrl;

    /** Data platform base URL */
    private String platformUrl;

    /** SSO login API path (appended to ssoUrl) */
    private String ssoLoginPath = "/api/sso_auth/";

    /** Ticket validation API path (appended to platformUrl) */
    private String ticketValidatePath = "/gw/v1/auth/validate_ticket";

    /** SQL query submission API path (appended to platformUrl) */
    private String queryBatchPath = "/gw/v1/dataquery/query/batch";

    /** SQL query result retrieval API path (appended to platformUrl) */
    private String queryResultPath = "/gw/v1/dataquery/query/result";

    /** Platform identifier used in SSO requests */
    private String platformName = "kiwi";

    /** Encrypted username for SSO login */
    private String username;

    /** Encrypted password for SSO login */
    private String password;

    /** RDS domain for query */
    private String domain;

    /** Database name */
    private String database;

    /** Source type for dataquery API */
    private int sourceType = 4;

    /** Number of tables to process per batch during DDL collection */
    private int collectBatchSize = 30;

    /** Number of tables to process per batch during DDL diff detection */
    private int diffBatchSize = 30;
}
