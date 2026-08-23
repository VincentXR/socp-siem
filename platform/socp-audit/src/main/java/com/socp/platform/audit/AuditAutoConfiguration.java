package com.socp.platform.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 审计自动配置：根据 socp.audit.sink 选择出口。
 *  - memory（默认，本地切片）：InMemoryAuditSink
 *  - kafka（Docker 环境）：KafkaAuditSink → socp-audit topic
 *
 * 【重要】spring-kafka 在本模块是 optional 依赖，本地切片运行时不在 classpath。
 * 因此 KafkaTemplate 绝不能出现在外层配置类的方法签名上——Spring 在做条件评估时会
 * 反射内省该类的全部方法，缺类会直接抛 NoClassDefFoundError 导致启动失败。
 * 正确做法：把涉及 Kafka 的 @Bean 收进嵌套静态配置类，并用 @ConditionalOnClass 守卫；
 * 该注解基于 ASM 读字节码元数据判定，不会触发类加载。
 */
@Configuration
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
        public AuditSink kafkaAuditSink(
                @Qualifier("auditKafkaTemplate")
                org.springframework.kafka.core.KafkaTemplate<String, String> template,
                @Value("${socp.audit.topic:socp-audit}") String topic,
                @Value("${socp.audit.fail-closed:false}") boolean failClosed) {
            return new KafkaAuditSink(template, topic, failClosed);
        }
    }
}
