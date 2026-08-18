package com.socp.detect.web.store;

import com.socp.platform.tenant.TenantContext;
import com.socp.rule.util.Json;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * 规则描述存储——JPA + H2 文件库（Flyway V1 建表），重启不丢；接口与原内存版一致。
 * 规则以 RuleSpec 的 JSON Map 形态保存（见 {@link com.socp.rule.config.RuleSpec}），spec 整体序列化为 JSON 列。
 */
@Component
public class RuleSpecStore {

    private final RuleRepository repo;

    /** 启动种子规则：与 com.siem Rules.defaultRules 同语义，以 JSON 配置形态表达 */
    private static final List<String> SEED_JSON = List.of(
            """
            {"id":"FW-SCAN","name":"高频阻断（疑似扫描）","type":"threshold","severity":"MEDIUM",
             "message":"源 {key} 在 {window} 内被拦截 {count} 次，疑似端口扫描/探测","keyField":"src_ip",
             "threshold":10,"window":"30s","mitre":"T1046",
             "match":[{"field":"source","op":"eq","value":"firewall"},
                      {"field":"action","op":"eq","value":"block"}]}
            """,
            """
            {"id":"AUTH-BRUTE","name":"SSH 暴力破解","type":"threshold","severity":"HIGH",
             "message":"源 {key} 在 {window} 内失败登录 {count} 次，疑似暴力破解","keyField":"src_ip",
             "threshold":5,"window":"60s","mitre":"T1110",
             "match":[{"field":"source","op":"eq","value":"auth"},
                      {"field":"msg","op":"contains","value":"Failed password"}]}
            """,
            """
            {"id":"AUTH-PRIVESC","name":"权限提升","type":"pattern","severity":"CRITICAL",
             "message":"检测到权限提升行为：{msg} @ {host}","mitre":"T1548",
             "match":[{"field":"msg","op":"regex","value":"(?i)Privilege escalation|sudo:"}]}
            """,
            """
            {"id":"WEB-ATTACK","name":"Web 攻击特征","type":"pattern","severity":"HIGH",
             "message":"疑似 Web 攻击：{msg} @ {host}","mitre":"T1190",
             "match":[{"field":"source","op":"eq","value":"web"},
                      {"field":"msg","op":"regex","value":"(?i)sqli|xss|注入|attack"}]}
            """,
            """
            {"id":"AUTH-BRUTE-SUCCESS","name":"暴力破解得手（关联）","type":"correlation","severity":"CRITICAL",
             "message":"源 {key} 在攻击链中疑似暴力破解得手","keyField":"src_ip","window":"120s","mitre":"T1078",
             "steps":[
                [{"field":"source","op":"eq","value":"auth"},{"field":"msg","op":"contains","value":"Failed password"}],
                [{"field":"source","op":"eq","value":"auth"},{"field":"msg","op":"contains","value":"Accepted password"}]
             ]}
            """,
            """
            {"id":"CRED-DUMP","name":"凭据转储","type":"pattern","severity":"CRITICAL",
             "message":"检测到凭据转储行为：{msg} @ {host}","mitre":"T1003",
             "match":[{"field":"msg","op":"regex","value":"(?i)mimikatz|lsass|sekurlsa|ntds|/etc/shadow"}]}
            """,
            """
            {"id":"MAL-C2","name":"疑似 C2 外联","type":"pattern","severity":"HIGH",
             "message":"疑似 C2 通信：{msg} @ {host}","mitre":"T1071",
             "match":[{"field":"source","op":"eq","value":"proxy"},
                      {"field":"msg","op":"regex","value":"(?i)beacon|c2|command-and-control|cobaltstrike"}]}
            """,
            """
            {"id":"RANSOM-ENCRYPT","name":"勒索加密行为","type":"threshold","severity":"CRITICAL",
             "message":"主机 {key} 在 {window} 内发生 {count} 次疑似加密改名，疑似勒索软件","keyField":"host",
             "threshold":20,"window":"60s","mitre":"T1486",
             "match":[{"field":"source","op":"eq","value":"edr"},
                      {"field":"msg","op":"regex","value":"(?i)locky|encrypted|wncry|ransom"}]}
            """,
            """
            {"id":"LATERAL-RDP","name":"异常横向移动","type":"threshold","severity":"HIGH",
             "message":"源 {key} 在 {window} 内向 {count} 个目标发起远程登录，疑似横向移动","keyField":"src_ip",
             "threshold":5,"window":"300s","mitre":"T1021",
             "match":[{"field":"msg","op":"regex","value":"(?i)rdp|psexec|wmiexec|smb session|3389"}]}
            """,
            """
            {"id":"EXFIL-LARGE","name":"大流量数据外传","type":"threshold","severity":"HIGH",
             "message":"实体 {key} 在 {window} 内发生 {count} 次大流量外传，疑似数据窃取","keyField":"src_ip",
             "threshold":3,"window":"300s","mitre":"T1041",
             "match":[{"field":"source","op":"eq","value":"netflow"},
                      {"field":"msg","op":"contains","value":"large_upload"}]}
            """,
            """
            {"id":"PERSIST-TASK","name":"计划任务持久化","type":"pattern","severity":"HIGH",
             "message":"检测到持久化计划任务：{msg} @ {host}","mitre":"T1053",
             "match":[{"field":"msg","op":"regex","value":"(?i)schtasks /create|crontab -e|at.exe|systemd-run"}]}
            """,
            """
            {"id":"EVADE-LOGCLEAR","name":"清除日志痕迹","type":"pattern","severity":"CRITICAL",
             "message":"检测到日志清除行为：{msg} @ {host}","mitre":"T1070",
             "match":[{"field":"msg","op":"regex","value":"(?i)wevtutil cl|clear-eventlog|history -c|rm -f /var/log"}]}
            """,
            """
            {"id":"PHISH-MAIL","name":"钓鱼邮件投递","type":"pattern","severity":"MEDIUM",
             "message":"检测到钓鱼邮件：{msg} @ {host}","mitre":"T1566",
             "match":[{"field":"source","op":"eq","value":"mail"},
                      {"field":"msg","op":"regex","value":"(?i)phish|suspicious attachment"}]}
            """,
            """
            {"id":"EXEC-SUSPICIOUS-SHELL","name":"可疑命令执行","type":"pattern","severity":"HIGH",
             "message":"检测到可疑命令执行：{msg} @ {host}","mitre":"T1059",
             "match":[{"field":"msg","op":"regex","value":"(?i)powershell.*(-enc|encodedcommand)|certutil -urlcache|invoke-expression|iex\\\\s*\\\\("}]}
            """,
            """
            {"id":"DOS-FLOOD","name":"拒绝服务洪泛","type":"threshold","severity":"HIGH",
             "message":"目标 {key} 在 {window} 内收到 {count} 次异常请求，疑似 DoS","keyField":"dst_ip",
             "threshold":100,"window":"30s","mitre":"T1498",
             "match":[{"field":"source","op":"eq","value":"firewall"},
                      {"field":"msg","op":"contains","value":"flood"}]}
            """,
            // ---------- UEBA：与实体自身历史基线比较，签名规则覆盖不到的检测面 ----------
            """
            {"id":"UEBA-AUTH-SPIKE","name":"UEBA 认证行为量突增","type":"baseline","severity":"HIGH",
             "message":"源 {key} 当前窗口认证事件 {count} 次，远超其自身基线 {baseline}（σ={stddev}，z={z}），行为显著偏离历史",
             "keyField":"src_ip","window":"60s","baselineWindows":12,"warmup":3,"sigma":3.0,"minCount":5,
             "mitre":"T1110",
             "match":[{"field":"source","op":"eq","value":"auth"}]}
            """,
            """
            {"id":"UEBA-USER-VOLUME","name":"UEBA 账号操作量异常","type":"baseline","severity":"MEDIUM",
             "message":"账号 {key} 当前窗口操作 {count} 次，基线仅 {baseline}（z={z}），疑似账号被盗用或批量导出",
             "keyField":"user","window":"300s","baselineWindows":12,"warmup":3,"sigma":2.5,"minCount":10,
             "mitre":"T1078",
             "match":[]}
            """,
            """
            {"id":"UEBA-NEW-GEO","name":"UEBA 账号首次异地登录","type":"rare","severity":"HIGH",
             "message":"账号 {key} 首次从 {value} 登录（历史仅见过 {known} 个地域），疑似凭据泄露",
             "keyField":"user","valueField":"geo","warmup":5,"mitre":"T1078",
             "match":[{"field":"source","op":"eq","value":"auth"}]}
            """,
            """
            {"id":"UEBA-NEW-PROCESS","name":"UEBA 主机首次执行新进程","type":"rare","severity":"MEDIUM",
             "message":"主机 {key} 首次执行进程 {value}（历史进程画像 {known} 个），疑似落地新载荷",
             "keyField":"host","valueField":"process","warmup":10,"mitre":"T1059",
             "match":[{"field":"source","op":"eq","value":"edr"}]}
            """,
            """
            {"id":"UEBA-NEW-DEST","name":"UEBA 主机首次外联新域名","type":"rare","severity":"MEDIUM",
             "message":"主机 {key} 首次外联 {value}（历史外联画像 {known} 个），疑似 C2 或数据外传",
             "keyField":"host","valueField":"dst_domain","warmup":10,"mitre":"T1071",
             "match":[{"field":"source","op":"eq","value":"proxy"}]}
            """,
            // ---------- 观察名单驱动：名单由运营侧动态维护，规则本身不用改 ----------
            """
            {"id":"WATCH-PRIV-ACCOUNT","name":"特权账号敏感操作（名单）","type":"pattern","severity":"HIGH",
             "message":"特权账号命中观察名单：{msg} @ {host}","mitre":"T1078",
             "match":[{"field":"user","op":"inlist","value":"privileged_accounts"},
                      {"field":"msg","op":"regex","value":"(?i)delete|drop|export|chmod 777|useradd"}]}
            """,
            """
            {"id":"WATCH-BLOCKED-IP","name":"封禁名单 IP 活动（名单）","type":"pattern","severity":"CRITICAL",
             "message":"封禁名单内的 IP {msg} 仍在活动 @ {host}","mitre":"T1071",
             "match":[{"field":"src_ip","op":"inlist","value":"blocked_ips"}]}
            """,
            """
            {"id":"WATCH-CROWN-JEWEL","name":"核心资产被访问（名单）","type":"threshold","severity":"HIGH",
             "message":"核心资产 {key} 在 {window} 内被访问 {count} 次，需重点确认","keyField":"dst_ip",
             "threshold":20,"window":"120s","mitre":"T1021",
             "match":[{"field":"dst_ip","op":"inlist","value":"crown_jewels"}]}
            """
    );

    public RuleSpecStore(RuleRepository repo) {
        this.repo = repo;
        if (repo.count() == 0) {
            Set<String> seededIds = new LinkedHashSet<>();
            // The versioned content pack is executable content, not metadata
            // only. Seed its specs first so a deployment runs the same rules
            // that CI validates and the workbench can inspect.
            Object rawRules = DetectionContentCatalog.manifest().get("rules");
            if (rawRules instanceof List<?> rules) {
                for (Object item : rules) {
                    if (item instanceof Map<?, ?> map && map.get("spec") instanceof Map<?, ?> rawSpec) {
                        Map<String, Object> spec = new LinkedHashMap<>();
                        rawSpec.forEach((key, value) -> spec.put(String.valueOf(key), value));
                        String id = String.valueOf(spec.getOrDefault("id", ""));
                        if (!id.isBlank()) {
                            save(spec);
                            seededIds.add(id);
                        }
                    }
                }
            }
            for (String json : SEED_JSON) {
                Map<String, Object> spec = Json.parseObject(json);
                if (!seededIds.contains(String.valueOf(spec.get("id")))) save(spec);
            }
        }
    }

    private String tenant() {
        String t = TenantContext.get();
        return t == null ? "default" : t;
    }

    public boolean isEmpty() {
        return repo.count() == 0;
    }

    public Map<String, Object> save(Map<String, Object> spec) {
        spec = DetectionContentCatalog.enrich(spec);
        // The current workbench still sends the legacy enabled toggle. Keep it
        // compatible with the lifecycle status while preserving explicit
        // TESTING/DRAFT/ARCHIVED states owned by detection engineering.
        if (spec.containsKey("enabled") && spec.get("status") != null) {
            String status = String.valueOf(spec.get("status")).toUpperCase();
            if ("ACTIVE".equals(status) || "DISABLED".equals(status)) {
                spec.put("status", Boolean.parseBoolean(String.valueOf(spec.get("enabled")))
                        ? "ACTIVE" : "DISABLED");
            }
        }
        Object id = spec.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            // 前端新建规则可不带 id，服务端生成
            spec = new LinkedHashMap<>(spec);
            spec.put("id", "RULE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        List<String> errors = DetectionContentCatalog.validateSpec(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("规则内容校验失败: " + String.join(", ", errors));
        }
        RuleEntity e = new RuleEntity();
        e.setId(String.valueOf(spec.get("id")));
        try {
            e.setSpec(Json.mapper().writeValueAsString(spec));
        } catch (Exception ex) {
            throw new IllegalStateException("规则 JSON 序列化失败: " + ex.getMessage(), ex);
        }
        e.setTenantId(tenant());
        repo.save(e);
        return spec;
    }

    public List<Map<String, Object>> list() {
        return repo.findByTenantId(tenant()).stream()
                .map(e -> DetectionContentCatalog.enrich(Json.parseObject(e.getSpec())))
                .toList();
    }

    public Map<String, Object> get(String id) {
        return repo.findByIdAndTenantId(id, tenant())
                .map(e -> DetectionContentCatalog.enrich(Json.parseObject(e.getSpec())))
                .orElse(null);
    }

    public Map<String, Object> contentManifest() {
        return DetectionContentCatalog.manifest();
    }

    public boolean delete(String id) {
        Optional<RuleEntity> e = repo.findByIdAndTenantId(id, tenant());
        if (e.isEmpty()) return false;
        repo.delete(e.get());
        return true;
    }
}
