package com.example.flashsale;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AppConfig {
    @Bean(destroyMethod = "shutdown")
    RedissonClient redisson(@Value("${app.redis.address}") String address) {
        Config config = new Config();
        config.setLockWatchdogTimeout(30000);
        config.useSingleServer().setAddress(address)
                .setConnectionMinimumIdleSize(2).setConnectionPoolSize(16)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setConnectTimeout(3000).setTimeout(3000).setRetryAttempts(2);
        return Redisson.create(config);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setTimeout(10);
        return template;
    }
}
