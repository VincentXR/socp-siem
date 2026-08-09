#!/usr/bin/env python3
"""批量生成 11 个业务服务骨架（可编译 @SpringBootApplication + health 端点）。"""
import os

BASE = r"D:\Program Files (x86)\WorkBuddy\siem\socp\services"

# (artifactId, package, context-path, class-name, human-name)
SERVICES = [
    ("soc-base",     "com.socp.soc",        "soc-base",     "SocBaseApplication",     "SOC 基础（租户/用户/角色/审计消费者，SOCP2）"),
    ("asset-web",      "com.socp.asset.web",    "asset-web",      "AssetWebApplication",      "ASSET Web（威胁情报/资产/案件，SAMP3）"),
    ("asset-collect",  "com.socp.asset.collect","asset-collect",  "AssetCollectApplication",  "ASSET 采集（SCP/CMDB/解密，SAMP4）"),
    ("search-config",   "com.socp.search.config", "search-config",   "GlsConfigApplication",   "SEARCH 配置（日志流水线/检索/解析，GLSP6）"),
    ("detect-web",      "com.socp.detect.web",    "detect-web",      "DetectWebApplication",      "DETECT Web（检测规则/匹配引擎，GASP8）"),
    ("detect-model",    "com.socp.detect.model",  "detect-model",    "DetectModelApplication",    "DETECT Model（Kafka 窗口聚合，GASP7）"),
    ("hips-web",     "com.socp.hips.web",   "hips-web",     "HipsWebApplication",     "HIPS Web（策略/心跳/端点资产，HIPSP9）"),
    ("hips-collect", "com.socp.hips.collect","hips-collect","HipsCollectApplication", "HIPS 采集（Agent注册/令牌/WS，HIPSP10）"),
    ("soar-web",     "com.socp.soar.web",   "soar-web",     "SoarWebApplication",     "SOAR Web（剧本编排/Temporal Saga，SOARP12）"),
    ("report-web",      "com.socp.report.web",    "report-web",      "SfmWebApplication",      "REPORT Web（报表/合规/工单，SFMP13）"),
    ("ai-assistant", "com.socp.ai",         "ai-assistant", "AiAssistantApplication", "AI 助手（LangChain4j 网关，AIP14）"),
]

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.socp</groupId>
        <artifactId>socp-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>{ARTIFACT}</artifactId>
    <name>{NAME}</name>
    <description>{DESC}</description>

    <dependencies>
        <dependency>
            <groupId>com.socp.platform</groupId>
            <artifactId>socp-error</artifactId>
        </dependency>
        <dependency>
            <groupId>com.socp.platform</groupId>
            <artifactId>socp-tenant</artifactId>
        </dependency>
        <dependency>
            <groupId>com.socp.platform</groupId>
            <artifactId>socp-audit</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
"""

APP = """package {PKG};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** {NAME}（{CTX}）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {{"{PKG}", "com.socp.platform"}})
public class {CLAZZ} {{
    public static void main(String[] args) {{
        SpringApplication.run({CLAZZ}.class, args);
    }}
}}
"""

HEALTH = """package {PKG};

import com.socp.platform.error.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 骨架健康/占位端点；业务接口按 P 提示词在其后扩展。 */
@RestController
public class HealthController {{
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {{
        return ApiResult.ok(Map.of("service", "{CTX}", "status", "UP"));
    }}
}}
"""

YML = """server:
  port: 8080
  servlet:
    context-path: /{CTX}

spring:
  application:
    name: {CTX}

management:
  endpoints:
    web:
      exposure:
        include: health,info
"""

for artifact, pkg, ctx, clazz, desc in SERVICES:
    d = os.path.join(BASE, artifact)
    src = os.path.join(d, "src", "main", "java", *pkg.split("."))
    res = os.path.join(d, "src", "main", "resources")
    os.makedirs(src, exist_ok=True)
    os.makedirs(res, exist_ok=True)
    with open(os.path.join(d, "pom.xml"), "w", encoding="utf-8") as f:
        f.write(POM.format(ARTIFACT=artifact, NAME=clazz.replace("Application", ""), DESC=desc))
    with open(os.path.join(src, clazz + ".java"), "w", encoding="utf-8") as f:
        f.write(APP.format(PKG=pkg, CTX=ctx, NAME=clazz.replace("Application", ""), CLAZZ=clazz))
    with open(os.path.join(src, "HealthController.java"), "w", encoding="utf-8") as f:
        f.write(HEALTH.format(PKG=pkg, CTX=ctx))
    with open(os.path.join(res, "application.yml"), "w", encoding="utf-8") as f:
        f.write(YML.format(CTX=ctx))

print("generated", len(SERVICES), "service skeletons")
