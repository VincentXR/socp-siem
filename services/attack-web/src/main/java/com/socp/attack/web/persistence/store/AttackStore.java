package com.socp.attack.web.persistence.store;


import com.socp.attack.web.persistence.repository.TacticRepository;
import com.socp.attack.web.persistence.repository.TechniqueRepository;
import com.socp.attack.web.persistence.entity.TacticEntity;
import com.socp.attack.web.persistence.entity.TechniqueEntity;
import com.socp.attack.web.domain.Tactic;
import com.socp.attack.web.domain.Technique;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ATT&CK catalog backed solely by {@code t_tactic}/{@code t_technique}.
 * Built-in entries seed an empty database on first startup.
 */
@Component
public class AttackStore {

    private final TacticRepository tacticRepository;
    private final TechniqueRepository techniqueRepository;

    public AttackStore(TacticRepository tacticRepository, TechniqueRepository techniqueRepository) {
        this.tacticRepository = tacticRepository;
        this.techniqueRepository = techniqueRepository;
    }

    @PostConstruct
    void seed() {
        if (tacticRepository.count() > 0) return;
            // Enterprise 矩阵完整 14 个战术，按 kill-chain 顺序
            addTactic("TA0043", "Reconnaissance", 1);
            addTactic("TA0042", "Resource Development", 2);
            addTactic("TA0001", "Initial Access", 3);
            addTactic("TA0002", "Execution", 4);
            addTactic("TA0003", "Persistence", 5);
            addTactic("TA0004", "Privilege Escalation", 6);
            addTactic("TA0005", "Defense Evasion", 7);
            addTactic("TA0006", "Credential Access", 8);
            addTactic("TA0007", "Discovery", 9);
            addTactic("TA0008", "Lateral Movement", 10);
            addTactic("TA0009", "Collection", 11);
            addTactic("TA0011", "Command and Control", 12);
            addTactic("TA0010", "Exfiltration", 13);
            addTactic("TA0040", "Impact", 14);

            add("T1595", "Active Scanning", "TA0043", "主动扫描目标网络/主机");
            add("T1592", "Gather Victim Host Information", "TA0043", "收集目标主机信息");
            add("T1589", "Gather Victim Identity Information", "TA0043", "收集目标身份信息（邮箱/账号）");
            add("T1583", "Acquire Infrastructure", "TA0042", "获取攻击基础设施（域名/VPS）");
            add("T1587", "Develop Capabilities", "TA0042", "自研恶意软件/证书等能力");
            add("T1588", "Obtain Capabilities", "TA0042", "获取现成工具/漏洞利用");
            add("T1190", "Exploit Public-Facing Application", "TA0001", "利用面向公网的应用漏洞获取初始访问");
            add("T1133", "External Remote Services", "TA0001", "通过 VPN/远程桌面等外部服务进入");
            add("T1566", "Phishing", "TA0001", "钓鱼邮件诱导执行恶意内容");
            add("T1059", "Command and Scripting Interpreter", "TA0002", "使用命令/脚本解释器执行代码");
            add("T1053", "Scheduled Task/Job", "TA0002", "利用计划任务/作业执行");
            add("T1547", "Boot or Logon Autostart Execution", "TA0003", "通过开机/登录自启动维持");
            add("T1543", "Create/Modify System Process", "TA0003", "创建/修改系统服务维持");
            add("T1556", "Modify Authentication Process", "TA0003", "修改认证过程窃取凭证");
            add("T1548", "Abuse Elevation Control Mechanism", "TA0004", "绕过 UAC 等提权");
            add("T1068", "Exploitation for Privilege Escalation", "TA0004", "利用漏洞提权");
            add("T1055", "Process Injection", "TA0004", "进程注入隐藏行为");
            add("T1078", "Valid Accounts", "TA0005", "使用合法账号规避检测");
            add("T1027", "Obfuscated Files or Information", "TA0005", "混淆文件/信息");
            add("T1070", "Indicator Removal", "TA0005", "清除日志等痕迹");
            add("T1110", "Brute Force", "TA0006", "暴力破解凭证");
            add("T1003", "OS Credential Dumping", "TA0006", "转储系统凭证");
            add("T1046", "Network Service Discovery", "TA0007", "探测网络服务");
            add("T1082", "System Information Discovery", "TA0007", "收集系统信息");
            add("T1083", "File and Directory Discovery", "TA0007", "枚举文件/目录");
            add("T1021", "Remote Services", "TA0008", "通过 SMB/RDP 等横向移动");
            add("T1570", "Lateral Tool Transfer", "TA0008", "横向传输工具");
            add("T1005", "Data from Local System", "TA0009", "收集本机数据");
            add("T1560", "Archive Collected Data", "TA0009", "归档收集的数据");
            add("T1071", "Application Layer Protocol", "TA0011", "应用层协议 C2");
            add("T1573", "Encrypted Channel", "TA0011", "加密通道 C2");
            add("T1105", "Ingress Tool Transfer", "TA0011", "入站工具传输");
            add("T1041", "Exfiltration Over C2 Channel", "TA0010", "经 C2 外泄");
            add("T1567", "Exfiltration Over Web Service", "TA0010", "经 Web 服务外泄");
            add("T1486", "Data Encrypted for Impact", "TA0040", "勒索加密数据");
            add("T1490", "Inhibit System Recovery", "TA0040", "破坏系统恢复能力");
            add("T1498", "Network Denial of Service", "TA0040", "网络拒绝服务");
    }

    private void addTactic(String id, String name, int order) {
        tacticRepository.save(new TacticEntity(id, name, order));
    }

    private void add(String id, String name, String tactic, String desc) {
        Technique t = new Technique(id, name, tactic,
                "https://attack.mitre.org/techniques/" + id + "/", desc);
        techniqueRepository.save(new TechniqueEntity(id, name, tactic, t.url(), desc));
    }

    public List<Tactic> tactics() {
        return tacticRepository.findAllByOrderBySortAsc().stream()
                .map(AttackStore::toTactic).toList();
    }

    public List<Technique> techniques() {
        return techniqueRepository.findAllByOrderByIdAsc().stream()
                .map(AttackStore::toTechnique).toList();
    }

    public Technique technique(String id) {
        return techniqueRepository.findById(id).map(AttackStore::toTechnique).orElse(null);
    }

    /** Update editable ATT&CK fields in the authoritative database row. */
    @Transactional
    public Technique update(String id, String name, String tactic, String url, String description) {
        TechniqueEntity entity = techniqueRepository.findById(id).orElse(null);
        if (entity == null) return null;
        Technique existing = toTechnique(entity);
        Technique updated = new Technique(id,
                valueOr(name, existing.name()), valueOr(tactic, existing.tactic()),
                valueOr(url, existing.url()), valueOr(description, existing.description()));
        entity.setName(updated.name());
        entity.setTactic(updated.tactic());
        entity.setUrl(updated.url());
        entity.setDescription(updated.description());
        techniqueRepository.save(entity);
        return updated;
    }

    /** 给定当前已启用规则覆盖的技术 ID 集合，计算各战术覆盖率与未覆盖技术。 */
    public Map<String, Object> coverage(java.util.Set<String> covered) {
        List<Tactic> tactics = tactics();
        List<Technique> techniques = techniques();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> byTactic = new ArrayList<>();
        int totalTech = techniques.size();
        int coveredTech = 0;
        for (Tactic tac : tactics) {
            List<Technique> inTactic = techniques.stream()
                    .filter(t -> t.tactic().equals(tac.id())).toList();
            long cov = inTactic.stream().filter(t -> covered.contains(t.id())).count();
            coveredTech += cov;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tactic", tac.id());
            row.put("name", tac.name());
            row.put("total", inTactic.size());
            row.put("covered", cov);
            row.put("coverage", inTactic.isEmpty() ? 0 : (int) Math.round(100.0 * cov / inTactic.size()));
            byTactic.add(row);
        }
        List<String> uncovered = techniques.stream()
                .filter(t -> !covered.contains(t.id()))
                .map(Technique::id).toList();
        out.put("byTactic", byTactic);
        out.put("totalTechniques", totalTech);
        out.put("coveredTechniques", coveredTech);
        out.put("coverage", totalTech == 0 ? 0 : (int) Math.round(100.0 * coveredTech / totalTech));
        out.put("uncovered", uncovered);
        return out;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Tactic toTactic(TacticEntity entity) {
        return new Tactic(entity.getId(), entity.getName(), entity.getSort());
    }

    private static Technique toTechnique(TechniqueEntity entity) {
        return new Technique(entity.getId(), entity.getName(), entity.getTactic(),
                entity.getUrl(), entity.getDescription());
    }
}
