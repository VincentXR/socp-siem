package com.socp.attack.web.store;

import com.socp.attack.web.domain.Tactic;
import com.socp.attack.web.domain.Technique;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ATT&CK 静态目录（内置子集，覆盖主流战术/技术）——内存 + H2 双写（t_tactic / t_technique）：
 * 首次启动灌种子，之后从库恢复，重启不丢。生产可从官方的 enterprise-attack.json 同步。
 */
@Component
public class AttackStore {

    private final TacticRepository tacticRepository;
    private final TechniqueRepository techniqueRepository;
    private final Map<String, Tactic> tactics = new LinkedHashMap<>();
    private final Map<String, Technique> techniques = new ConcurrentHashMap<>();
    private final List<Technique> order = new ArrayList<>();

    public AttackStore(TacticRepository tacticRepository, TechniqueRepository techniqueRepository) {
        this.tacticRepository = tacticRepository;
        this.techniqueRepository = techniqueRepository;
    }

    @PostConstruct
    void seed() {
        List<TacticEntity> ts = tacticRepository.findAll();
        if (ts.isEmpty()) {
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
        } else {
            for (TacticEntity e : ts) {
                tactics.put(e.getId(), new Tactic(e.getId(), e.getName(), e.getSort()));
            }
            for (TechniqueEntity e : techniqueRepository.findAll()) {
                Technique t = new Technique(e.getId(), e.getName(), e.getTactic(), e.getUrl(), e.getDescription());
                techniques.put(t.id(), t);
                order.add(t);
            }
        }
    }

    private void addTactic(String id, String name, int order) {
        tactics.put(id, new Tactic(id, name, order));
        tacticRepository.save(new TacticEntity(id, name, order));
    }

    private void add(String id, String name, String tactic, String desc) {
        Technique t = new Technique(id, name, tactic,
                "https://attack.mitre.org/techniques/" + id + "/", desc);
        techniques.put(id, t);
        order.add(t);
        techniqueRepository.save(new TechniqueEntity(id, name, tactic, t.url(), desc));
    }

    public List<Tactic> tactics() {
        return new ArrayList<>(tactics.values());
    }

    public List<Technique> techniques() {
        return new ArrayList<>(order);
    }

    public Technique technique(String id) {
        return techniques.get(id);
    }

    /** 给定当前已启用规则覆盖的技术 ID 集合，计算各战术覆盖率与未覆盖技术。 */
    public Map<String, Object> coverage(java.util.Set<String> covered) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> byTactic = new ArrayList<>();
        int totalTech = order.size();
        int coveredTech = 0;
        for (Tactic tac : tactics.values()) {
            List<Technique> inTactic = order.stream()
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
        List<String> uncovered = order.stream()
                .filter(t -> !covered.contains(t.id()))
                .map(Technique::id).toList();
        out.put("byTactic", byTactic);
        out.put("totalTechniques", totalTech);
        out.put("coveredTechniques", coveredTech);
        out.put("coverage", totalTech == 0 ? 0 : (int) Math.round(100.0 * coveredTech / totalTech));
        out.put("uncovered", uncovered);
        return out;
    }
}
