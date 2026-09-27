package com.socp.platform.audit.config;

import com.socp.platform.audit.sink.AuditKafkaRuntime;
import com.socp.platform.audit.sink.AuditOutboxPublisher;
import com.socp.platform.audit.sink.InMemoryAuditSink;
import com.socp.platform.audit.sink.JdbcAuditOutboxSink;
import com.socp.platform.audit.sink.KafkaAuditSink;
import com.socp.platform.audit.spi.AuditSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计自动配置：根据 socp.audit.sink 选择出口。
 *  - memory（默认，本地切片）：InMemoryAuditSink
 *  - kafka（Docker 环境）：KafkaAuditSink → socp-audit topic
 *
 * 【重要】Kafka/JDBC beans live in a guarded nested configuration so the
 * memory sink stays independent of infrastructure beans and stateless services
 * can retain direct Kafka delivery when they have no transaction to join.
 */
@Configuration
@EnableScheduling
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "socp.audit.sink", havingValue = "memory", matchIfMissing = true)
    public AuditSink memoryAuditSink() {
        return new InMemoryAuditSink();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    @ConditionalOnProperty(name = "socp.audit.sink", havingValue = "kafka")
    static class KafkaAuditConfiguration {

        @Bean("auditProducerFactory")
        public org.springframework.kafka.core.ProducerFactory<String, String> auditProducerFactory(
                @Value("${socp.kafka.bootstrap:${spring.kafka.bootstrap-servers:localhost:9092}}")
                String bootstrap) {
            Map<String, Object> properties = new HashMap<>();
            properties.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            properties.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class);
            properties.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class);
            properties.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
            properties.put(org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            return new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(properties);
        }

        @Bean("auditKafkaTemplate")
        public org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate(
                @Qualifier("auditProducerFactory")
                org.springframework.kafka.core.ProducerFactory<String, String> producerFactory) {
            return new org.springframework.kafka.core.KafkaTemplate<>(producerFactory);
        }

        @Bean
        public AuditKafkaRuntime auditKafkaRuntime(
                ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbcProvider,
                ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManagerProvider,
                @Qualifier("auditKafkaTemplate")
                org.springframework.kafka.core.KafkaTemplate<String, String> template,
                @Value("${socp.audit.topic:socp-audit}") String topic,
                @Value("${socp.audit.fail-closed:false}") boolean failClosed,
                @Value("${socp.audit.outbox.batch-size:100}") int batchSize,
                @Value("${socp.audit.outbox.max-attempts:0}") int maxAttempts,
                @Value("${socp.audit.outbox.claim-timeout:PT5M}") String claimTimeout) {
            org.springframework.jdbc.core.JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
            org.springframework.transaction.PlatformTransactionManager transactionManager =
                    transactionManagerProvider.getIfAvailable();
            if (jdbc != null && transactionManager != null) {
                JdbcAuditOutboxSink sink = new JdbcAuditOutboxSink(jdbc);
                AuditOutboxPublisher publisher = new AuditOutboxPublisher(jdbc, transactionManager,
                        template, topic, batchSize, maxAttempts, Duration.parse(claimTimeout));
                return new AuditKafkaRuntime(sink, publisher);
            }
            return new AuditKafkaRuntime(new KafkaAuditSink(template, topic, failClosed), null);
        }

        @Bean
        public AuditSink kafkaAuditSink(AuditKafkaRuntime runtime) {
            return runtime.sink();
        }
    }
}
