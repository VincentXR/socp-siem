package com.socp.ai.service;

import com.socp.ai.llm.LlmChatClient;
import com.socp.ai.model.AiResult;
import com.socp.ai.store.QaEntity;
import com.socp.ai.store.QaRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * AI 助手服务：支持 LLM（本地 Ollama / 远程大模型）与本地高质量安全知识库双模混合引擎。
 */
@Service
public class AiAssistantService {

    private final QaRepository repository;
    private final LlmChatClient llmClient;

    @Autowired
    public AiAssistantService(QaRepository repository, LlmChatClient llmClient) {
        this.repository = repository;
        this.llmClient = llmClient;
    }

    public AiAssistantService(QaRepository repository) {
        this(repository, null);
    }

    @PostConstruct
    void init() {
        if (repository.count() == 0) {
            seed("暴力破解", "暴力破解检测建议配置阈值规则 AUTH-BRUTE：同一源 IP 在 60s 内失败登录 >= 5 次即告警（MITRE T1110）。关联规则 AUTH-BRUTE-SUCCESS 可检测失败后成功登录。\n建议：1) 检查防火墙/查找表是否已封禁高频失败 IP；2) SSH 是否禁用密码登录改用密钥；3) 对 admin/root 等关键人员账号启用 MFA。");
            seed("端口扫描", "端口扫描检测建议配置阈值规则 FW-SCAN：同一源 IP 在 30s 内被防火墙阻断 >= 10 次即告警（MITRE T1046）。\n建议：确认来源是否为授权安全测试；非授权则加入封禁名单并工单跟进。");
            seed("SQL注入", "SQL 注入检测建议配置模式规则 WEB-ATTACK：正则匹配 'UNION SELECT|OR 1=1|--|;<script>' 等（MITRE T1190）。\n建议：1) 检查 WAF 是否已拦截；2) 应用层是否参数化查询；3) 排查是否有敏感数据通过报错泄露。");
            seed("提权", "权限提升检测建议配置模式规则 AUTH-PRIVESC：匹配 sudo/Privilege escalation 关键字且 severity >= HIGH（MITRE T1068/T1548）。\n建议：审计 sudoers 与 SUID 二进制；核对是否为变更窗口内的合法运维。");
            seed("恶意软件", "恶意软件检测建议：终端上报的样本哈希与威胁情报 IOC（SHA256/MD5）比对（MITRE T1204）。\n建议：1) 隔离主机；2) 用 threat-web 查询哈希是否已知恶意；3) 取证并清除持久化项。");
            seed("钓鱼", "钓鱼邮件检测建议：邮件网关提取发件域/URL 与威胁情报比对（MITRE T1566）。\n建议：1) 阻断发件域名；2) 通知收件人改密；3) 检索同域名的其余邮件。");
            seed("勒索", "勒索软件检测建议配置模式规则 RANSOM：匹配 'locked|encrypted|*.crypt|vssadmin delete' 等（MITRE T1486/T1490）。\n建议：立即隔离主机、断开共享、检查备份可用性、启动事件响应预案并建案。");
            seed("拒绝服务", "拒绝服务（DDoS）检测建议阈值规则 NET-DOS：单位时间内入站连接/请求突增（MITRE T1498）。\n建议：启用清洗/限速；确认源是否为僵尸网络（查威胁情报 Tor/C2 名单）。");
            seed("横向移动", "横向移动检测建议关联规则 LATERAL-MOVE：同一源在短时间登录多台主机/SMB+RDP（MITRE T1021）。\n建议：确认是否为运维跳板；否则隔离源主机并核查凭据是否泄露。");
            seed("数据外泄", "数据外泄检测建议阈值/模式规则 DATA-EXFIL：大流量外发或命中 C2 域名（MITRE T1041/T1567）。\n建议：阻断出口、查威胁情报 C2 名单、核实业务必要性、必要时启动事件响应。");
            seed("命令控制", "C2 通信检测建议：出向连接命中威胁情报 C2 域名/IP（MITRE T1071/T1573）。\n建议：阻断域名、隔离主机、检索同 C2 的其他受害主机、提取样本。");
            seed("凭据盗取", "凭据盗取检测建议模式规则 CREDS-DUMP：匹配 'mimikatz|sekurlsa|/etc/shadow' 等（MITRE T1003）。\n建议：强制相关账号改密、排查内存 dump 来源、核查凭据是否被复用。");
            seed("ATT&CK", "MITRE ATT&CK 是攻击战术/技术的通用语言。本平台在 attack-web 提供战术/技术目录与检测覆盖率；规则可在 DETECT 中通过 mitre 字段关联技术（如 T1110 暴力破解、T1190 面向公网应用利用）。覆盖率看板可定位尚未被检测覆盖的技术。");
            seed("应急", "事件响应建议流程：1) 确认与分级（ALERT 告警中心）；2)  containment 隔离失陷主机（SOAR 剧本可自动封禁）；3) 清除持久化；4) 恢复并验证；5) 复盘建案（incident-web 时间线）并补检测规则（DETECT）。");
        }
    }

    private void seed(String keyword, String answer) {
        repository.save(new QaEntity(keyword, answer));
    }

    public AiResult ask(String question) {
        long start = System.currentTimeMillis();
        String q = question == null ? "" : question.trim();

        // 1. 若配置并启用了大模型，优先请求 LLM
        if (llmClient != null && llmClient.isEnabled()) {
            Optional<String> llmAnswer = llmClient.chat(q);
            if (llmAnswer.isPresent()) {
                String suggestion = "已通过 AI 专家大模型进行智能研判。可结合 SEARCH 日志检索或在 DETECT 中创建检测规则。";
                return new AiResult(q, llmAnswer.get(), suggestion, System.currentTimeMillis() - start);
            }
        }

        // 2. 本地安全知识库精准匹配
        String answer = "暂无匹配的安全知识。可尝试提问：暴力破解、端口扫描、SQL注入、提权、恶意软件、钓鱼、勒索、DDoS、横向移动、数据外泄、C2、凭据盗取、ATT&CK、应急响应等。";
        String suggestion = null;

        var match = repository.findMatches(q, PageRequest.of(0, 1)).stream().findFirst();
        if (match.isPresent()) {
            answer = match.get().getAnswer();
            suggestion = "是否需要我帮你在 DETECT 规则引擎中创建对应的检测规则，或在 SOAR 中编排响应剧本？";
        }

        // 3. 通用问答知识兜底
        if (answer.startsWith("暂无") && !q.isEmpty()) {
            answer = "关于「" + q + "」：当前知识库暂无直接匹配。安全运营建议：1) 检查 DETECT 规则引擎是否已覆盖该场景；2) 查看 ALERT 告警中心是否有相关告警；3) 查看 SEARCH 日志检索是否有异常痕迹；4) 用 ATT&CK 框架对齐攻击阶段。";
            suggestion = "可尝试在 SEARCH 中搜索相关日志关键字，或在 DETECT 中新建检测规则。";
        }

        return new AiResult(q, answer, suggestion, System.currentTimeMillis() - start);
    }
}
