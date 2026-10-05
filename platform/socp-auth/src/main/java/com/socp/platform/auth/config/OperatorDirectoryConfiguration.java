package com.socp.platform.auth.config;

import com.socp.platform.auth.security.OperatorDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared membership provisioning, with no servlet or reactive web dependencies. */
@Configuration(proxyBeanMethods = false)
public class OperatorDirectoryConfiguration {
    @Bean
    public OperatorDirectory operatorDirectory(
            @Value("${socp.auth.operator-directory:${SOCP_OPERATOR_DIRECTORY:}}") String directory,
            @Value("${socp.auth.users:}") String users,
            @Value("${socp.auth.roles:}") String roles) {
        return new OperatorDirectory(directory, users, roles);
    }
}
