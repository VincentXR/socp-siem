package com.socp.platform.client.config;


import com.socp.platform.client.service.SocpService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端口表一致性守卫：{@link SocpService} 的默认端口必须与 {@code build/ports.env} 完全一致。
 *
 * <p>脚本侧（bash / python / vite）读 ports.env，Java 侧读 SocpService——两张表一旦漂移，
 * 就会出现「脚本按 28080 起服务、服务间调用仍打 18080」这种极难排查的问题。
 * 这个测试让漂移在构建期就失败，而不是等到运行时静默丢数据。
 */
class PortsEnvConsistencyTest {

    private static final Pattern PORT_LINE =
            Pattern.compile("^SOCP_PORT_([A-Z0-9_]+)=\"\\$\\{SOCP_PORT_\\1:-(\\d+)}\"\\s*$");

    /** ports.env 里不属于后端服务的条目（前端 dev server）。 */
    private static final String FRONTEND_KEY = "FRONTEND_WORKBENCH";

    @Test
    void socpServicePortsMatchPortsEnv() throws IOException {
        Path portsEnv = Path.of("..", "..", "build", "ports.env").normalize();
        assumeTrue(Files.exists(portsEnv), "未找到 build/ports.env（非仓库内构建），跳过一致性校验");

        Map<String, Integer> fromEnv = new LinkedHashMap<>();
        for (String line : Files.readAllLines(portsEnv, StandardCharsets.UTF_8)) {
            Matcher m = PORT_LINE.matcher(line.trim());
            if (!m.matches()) continue;
            String key = m.group(1);
            if (FRONTEND_KEY.equals(key)) continue;
            fromEnv.put(key.toLowerCase().replace('_', '-'), Integer.parseInt(m.group(2)));
        }
        assertTrue(fromEnv.size() >= SocpService.values().length,
                "ports.env 解析到的服务端口过少（" + fromEnv.size() + "），格式可能被改动");

        for (SocpService svc : SocpService.values()) {
            Integer expected = fromEnv.get(svc.serviceName());
            assertTrue(expected != null,
                    "build/ports.env 缺少服务 " + svc.serviceName() + " 的端口定义");
            assertEquals(expected.intValue(), svc.defaultPort(),
                    "服务 " + svc.serviceName() + " 端口在 SocpService 与 build/ports.env 之间不一致");
            fromEnv.remove(svc.serviceName());
        }
        assertTrue(fromEnv.isEmpty(),
                "build/ports.env 中存在 SocpService 未覆盖的服务: " + fromEnv.keySet());
    }
}
