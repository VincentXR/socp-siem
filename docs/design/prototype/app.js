/* ============================================================================
   SOCP Workbench prototype — vanilla ES2020, no dependencies.
   Sections: data · nav · view switching · overview · alarms · search ·
             drawer · palette · theme · utilities
   ========================================================================== */
(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const reduceMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------------------------------------------------------------- 1. Data */

  const NAV = [
    { group: '监控', items: [
      { id: 'overview', label: '态势总览', icon: 'i-grid' },
      { id: 'situation', label: '态势大屏', icon: 'i-chart' },
      { id: 'alarms', label: '告警队列', icon: 'i-bell', badge: '128' },
      { id: 'attack', label: '攻击视图', icon: 'i-target' },
      { id: 'ueba', label: 'UEBA 异常', icon: 'i-brain' }
    ]},
    { group: '调查', items: [
      { id: 'search', label: '日志检索', icon: 'i-search' },
      { id: 'cases', label: '案件', icon: 'i-case' },
      { id: 'assets', label: '资产', icon: 'i-server' },
      { id: 'endpoints', label: '端点', icon: 'i-endpoint' },
      { id: 'threat', label: '威胁情报', icon: 'i-radar' }
    ]},
    { group: '检测工程', items: [
      { id: 'detect', label: '检测规则', icon: 'i-rules' },
      { id: 'ingest', label: '接入与解析', icon: 'i-ingest' },
      { id: 'refset', label: '参考集', icon: 'i-list' },
      { id: 'meta', label: '元数据', icon: 'i-meta' }
    ]},
    { group: '响应与合规', items: [
      { id: 'soar', label: '编排响应', icon: 'i-soar' },
      { id: 'notify', label: '通知', icon: 'i-notify' },
      { id: 'report', label: '报表', icon: 'i-chart' },
      { id: 'compliance', label: '合规', icon: 'i-shield' }
    ]},
    { group: '工作台', items: [
      { id: 'ai', label: 'AI 助手', icon: 'i-spark' },
      { id: 'system', label: '设计系统', icon: 'i-palette' }
    ]}
  ];

  const VIEW_META = {
    overview: { group: '监控', title: '态势总览' },
    alarms: { group: '监控', title: '告警队列' },
    search: { group: '调查', title: '日志检索' },
    system: { group: '设计交付', title: '设计系统' }
  };

  const STUB_BLURB = {
    situation: '独立路由的全屏变体：墙面投屏与值班巡屏用，隐藏导航、只保留指标与时间轴，不承担写操作。',
    attack: 'ATT&CK 全矩阵：可下钻到战术—技术—规则三级，命中数据与总览热力图同源。',
    ueba: '行为基线：按实体呈现偏离度曲线与同类peer对比，异常点直接跳转检索。',
    cases: '案件：调查控制区与时间线分离，证据可挂载/摘除告警，写操作要求原因与稳定重试键。',
    assets: '资产：关键性分级驱动风险权重，资产详情聚合告警、端点与暴露面。',
    endpoints: '端点：隔离、取证与进程树并列，危险操作二次确认并保留审计回执。',
    threat: '威胁情报：IOC 生命周期与命中回溯，命中自动回写实体风险分。',
    detect: '检测规则：版本化内容包，草稿—评审—发布三段式，附规则健康度。',
    ingest: '接入与解析：管线可视化，解析失败样本可直接回放验证。',
    refset: '参考集：条目批量写入保持抽屉不关闭，失败条目保留选中以便重试。',
    meta: '元数据：字段字典与规范化映射的唯一事实源。',
    soar: '编排响应：画布快捷键仅在编辑器内生效，执行回执与审批分离。',
    notify: '通知：通道健康度与静默窗口，确认只描述后续投递行为。',
    report: '报表：日报与趋势独立加载，单源失败保留其余成功结果。',
    compliance: '合规：控制项到检测规则的可追溯映射，缺口按严重度排序。',
    ai: 'AI 助手：结论与证据分离呈现，引用只能解析到同源证据接口。'
  };

  const ALARMS = [
    { id: 'ALM-2026-1042', sev: 'CRITICAL', title: 'SMB 暴力破解后成功登录，疑似横向移动',
      rule: 'win-lateral-smb-bruteforce', entity: 'db-prod-07', etype: 'host', risk: 94,
      status: 'OPEN', owner: null, time: '00:39', ago: '2 分钟前', mitre: 'T1021.002',
      desc: '同一源地址在 4 分钟内对 3 台主机发起 219 次 SMB 认证失败，随后在 db-prod-07 出现一次成功登录并创建服务。',
      timeline: [
        { t: '00:35:02', tone: 'critical', title: 'SMB 认证失败 ×219', desc: '源 203.0.113.44 → 3 台主机，账户 svc_backup' },
        { t: '00:38:47', tone: 'critical', title: '认证成功', desc: 'db-prod-07 · svc_backup · NTLM', code: 'event.code=4624  logon_type=3' },
        { t: '00:39:12', tone: '', title: '服务创建', desc: 'db-prod-07 · 服务名 UpdaterSvc，二进制指向 C:\\Windows\\Temp\\upd.exe', code: 'event.code=7045' },
        { t: '00:39:14', tone: 'ok', title: '检测命中', desc: '规则 win-lateral-smb-bruteforce v12 · 38s 内完成评估' }
      ]},
    { id: 'ALM-2026-1041', sev: 'CRITICAL', title: '卷影副本删除 + 批量文件重命名',
      rule: 'ransom-shadowcopy-massrename', entity: 'file-srv-02', etype: 'host', risk: 91,
      status: 'INVESTIGATING', owner: '林可', time: '00:35', ago: '6 分钟前', mitre: 'T1490',
      desc: '进程 vssadmin.exe 删除全部卷影副本，同一主机 90 秒内出现 1,842 次文件重命名，扩展名统一改为 .locked。',
      timeline: [
        { t: '00:33:51', tone: 'critical', title: '卷影副本删除', desc: 'vssadmin delete shadows /all /quiet', code: 'event.code=4688 parent=cmd.exe' },
        { t: '00:34:20', tone: 'critical', title: '批量重命名 1,842 次', desc: '目录 D:\\share 递归，扩展名 → .locked' },
        { t: '00:35:08', tone: 'ok', title: '已认领', desc: '林可 · 转为 INVESTIGATING' }
      ]},
    { id: 'ALM-2026-1039', sev: 'HIGH', title: 'AssumeRole 调用来自异常区域',
      rule: 'cloudtrail-priv-esc-anomaly', entity: 'svc-deploy@prod', etype: 'identity', risk: 86,
      status: 'OPEN', owner: null, time: '00:31', ago: '10 分钟前', mitre: 'T1078.004',
      desc: '服务账户首次从 ap-southeast-1 发起 AssumeRole，目标角色具备 S3 全读写权限，源 IP 不在既有出口清单。',
      timeline: [
        { t: '00:30:11', tone: 'critical', title: 'AssumeRole', desc: 'role/deploy-admin · 来源 ap-southeast-1 · 203.0.113.44' },
        { t: '00:31:00', tone: 'ok', title: '基线偏离命中', desc: '该身份 90 天内仅出现在 cn-north-1' }
      ]},
    { id: 'ALM-2026-1037', sev: 'HIGH', title: '编码 PowerShell 下载执行',
      rule: 'edr-psh-encoded-download', entity: 'ws-1042', etype: 'host', risk: 82,
      status: 'OPEN', owner: null, time: '00:28', ago: '13 分钟前', mitre: 'T1059.001',
      desc: 'powershell.exe 以 -enc 启动，解码后从远程地址下载可执行文件并直接调用，父进程为 winword.exe。',
      timeline: [
        { t: '00:27:40', tone: 'critical', title: '子进程创建', desc: 'winword.exe → powershell.exe -nop -w hidden -enc …' },
        { t: '00:28:02', tone: '', title: '网络外联', desc: '185.199.110.153:443 · 响应为 PE 文件' }
      ]},
    { id: 'ALM-2026-1035', sev: 'HIGH', title: '域控账户非工作时段交互登录',
      rule: 'ad-baseline-offhours-logon', entity: 'j.wu', etype: 'identity', risk: 78,
      status: 'INVESTIGATING', owner: '周骏', time: '00:21', ago: '20 分钟前', mitre: 'T1078',
      desc: '特权组成员在 00:21 完成交互式登录，该账户历史登录窗口为 08:30–19:30，本次来源为未登记工作站。',
      timeline: [
        { t: '00:20:33', tone: '', title: '交互登录', desc: 'DC-01 · 登录类型 2 · 来源 WS-3312' },
        { t: '00:21:05', tone: 'ok', title: '已认领', desc: '周骏 · 正在联系账户持有人' }
      ]},
    { id: 'ALM-2026-1033', sev: 'MEDIUM', title: 'WAF SQL 注入尝试激增',
      rule: 'waf-sqli-burst', entity: 'api-gw-01', etype: 'asset', risk: 64,
      status: 'OPEN', owner: null, time: '00:17', ago: '24 分钟前', mitre: 'T1190',
      desc: '单一源在 10 分钟内触发 312 次 SQLi 规则，全部被拦截，未观察到响应码 5xx 上升。',
      timeline: [
        { t: '00:07:12', tone: '', title: '规则触发密集', desc: '源 198.51.100.7 · 目标 /api/v2/query' },
        { t: '00:17:00', tone: 'ok', title: '无成功迹象', desc: '5xx 比例 0.02%，与基线一致' }
      ]},
    { id: 'ALM-2026-1031', sev: 'MEDIUM', title: '高频 TXT 查询，疑似 DNS 隧道',
      rule: 'dns-tunnel-txt-rate', entity: 'app-node-11', etype: 'host', risk: 61,
      status: 'OPEN', owner: null, time: '00:12', ago: '29 分钟前', mitre: 'T1071.004',
      desc: '单主机 TXT 查询速率升至 46/min，子域长度集中在 48–62 字符，解析目标为同一权威服务器。',
      timeline: [
        { t: '00:11:20', tone: '', title: '查询速率异常', desc: 'app-node-11 → ns1.tunnel-example.net' },
        { t: '00:12:00', tone: 'ok', title: '阈值命中', desc: '基线 3/min，当前 46/min' }
      ]},
    { id: 'ALM-2026-1029', sev: 'MEDIUM', title: '单源 5 分钟内失败登录 240 次',
      rule: 'ssh-bruteforce-threshold', entity: '203.0.113.44', etype: 'ip', risk: 58,
      status: 'SUPPRESSED', owner: '林可', time: '00:04', ago: '37 分钟前', mitre: 'T1110.001',
      desc: '攻击源已被边界 ACL 阻断，处于维护窗口抑制期内，抑制到期时间 2026-10-21 06:00。',
      timeline: [
        { t: '00:03:12', tone: '', title: '阈值命中', desc: '240 次失败 / 300 秒' },
        { t: '00:04:00', tone: 'ok', title: '已抑制', desc: '林可 · 原因：边界已阻断，窗口 6 小时' }
      ]},
    { id: 'ALM-2026-1027', sev: 'LOW', title: '新增本地管理员组成员',
      rule: 'win-local-admin-add', entity: 'ws-2210', etype: 'host', risk: 41,
      status: 'OPEN', owner: null, time: '23:58', ago: '44 分钟前', mitre: 'T1098',
      desc: '账户 helpdesk-03 将域账户 t.shen 加入本地 Administrators 组，未关联变更单。',
      timeline: [
        { t: '23:57:31', tone: '', title: '组成员变更', desc: 'ws-2210 · Administrators += t.shen' }
      ]},
    { id: 'ALM-2026-1025', sev: 'LOW', title: 'certutil 远程下载',
      rule: 'lolbin-certutil-urlcache', entity: 'ws-0771', etype: 'host', risk: 37,
      status: 'RESOLVED', owner: '陈默', time: '23:41', ago: '1 小时前', mitre: 'T1105',
      desc: 'IT 运维通过 certutil 下载驱动包，已提供变更单 CHG-8821，判定为非恶意。',
      timeline: [
        { t: '23:40:02', tone: '', title: 'URL 缓存下载', desc: 'certutil -urlcache -f http://intranet/drivers.cab' },
        { t: '23:41:10', tone: 'ok', title: '已解决', desc: '陈默 · 分类：已授权运维操作' }
      ]},
    { id: 'ALM-2026-1023', sev: 'INFO', title: '检测内容包更新完成',
      rule: 'content-pack-update', entity: 'detect-web', etype: 'service', risk: 8,
      status: 'CLOSED', owner: '系统', time: '23:20', ago: '1 小时前', mitre: '—',
      desc: '内容包 socp-core-detections 更新至 2026.09.13，39 条规则全部生效。',
      timeline: [
        { t: '23:20:00', tone: 'ok', title: '发布完成', desc: '39 ACTIVE · manifest 校验通过' }
      ]},
    { id: 'ALM-2026-1021', sev: 'HIGH', title: 'Kerberoasting：异常 SPN 票据请求',
      rule: 'ad-kerberoasting-spn', entity: 'svc-sql@corp', etype: 'identity', risk: 84,
      status: 'OPEN', owner: null, time: '23:12', ago: '1 小时前', mitre: 'T1558.003',
      desc: '单一账户在 2 分钟内请求 41 个不同 SPN 的 RC4 票据，请求方主机未加入域。',
      timeline: [
        { t: '23:11:44', tone: 'critical', title: 'SPN 票据请求 ×41', desc: '加密类型 0x17 (RC4) · 请求方 WS-9002' }
      ]},
    { id: 'ALM-2026-1019', sev: 'HIGH', title: '堡垒机会话中出现批量导出命令',
      rule: 'pam-bulk-export-command', entity: 'bastion-01', etype: 'asset', risk: 80,
      status: 'OPEN', owner: null, time: '22:54', ago: '2 小时前', mitre: 'T1005',
      desc: '运维会话在 90 秒内连续执行 6 次 mysqldump，导出目标为外部对象存储，未关联变更单。',
      timeline: [
        { t: '22:53:10', tone: '', title: '命令序列命中', desc: 'mysqldump ×6 · 目标 s3://ext-backup-*' }
      ]},
    { id: 'ALM-2026-1017', sev: 'MEDIUM', title: '对象存储桶 ACL 改为公开读',
      rule: 's3-acl-public-read', entity: 'prod-logs-bucket', etype: 'asset', risk: 66,
      status: 'OPEN', owner: null, time: '22:40', ago: '2 小时前', mitre: 'T1530',
      desc: 'PutBucketAcl 将 prod-logs-bucket 设为 public-read，桶内包含 30 天访问日志。',
      timeline: [
        { t: '22:39:51', tone: 'critical', title: 'ACL 变更', desc: 'principal deploy-bot · public-read' }
      ]},
    { id: 'ALM-2026-1015', sev: 'LOW', title: '非工作时段 VPN 登录来自新设备',
      rule: 'vpn-new-device-offhours', entity: 'l.zhao', etype: 'identity', risk: 44,
      status: 'OPEN', owner: null, time: '22:31', ago: '2 小时前', mitre: 'T1078',
      desc: '账户首次从 macOS 客户端登录，出口 IP 位于未登记 ASN，MFA 已通过。',
      timeline: [
        { t: '22:30:22', tone: '', title: 'VPN 登录', desc: '新设备指纹 · MFA 通过 · ASN 4134' }
      ]}
  ];

  const SEV_META = {
    CRITICAL: { label: 'CRITICAL', short: '严重' },
    HIGH: { label: 'HIGH', short: '高' },
    MEDIUM: { label: 'MEDIUM', short: '中' },
    LOW: { label: 'LOW', short: '低' },
    INFO: { label: 'INFO', short: '提示' }
  };
  const STATUS_CLASS = { OPEN: 'open', INVESTIGATING: 'inv', RESOLVED: 'res', CLOSED: 'closed', SUPPRESSED: 'closed' };

  const FIELDS = [
    ['@timestamp', '1,284'], ['event.dataset', '1,284'], ['host.name', '212'],
    ['user.name', '148'], ['source.ip', '203'], ['destination.ip', '96'],
    ['process.command_line', '640'], ['event.code', '37'], ['rule.name', '24'],
    ['severity', '5'], ['mitre.technique', '19'], ['agent.version', '3'],
    ['tenant.id', '1'], ['cloud.account.id', '7']
  ];

  const EVENTS = [
    { time: '00:39:12.418', sev: 'CRITICAL', dataset: 'windows.security', host: 'db-prod-07',
      summary: '4624 登录成功 · svc_backup · 登录类型 3',
      tags: ['T1021.002', 'NTLM'],
      json: { '@timestamp': '2026-10-21T00:39:12.418Z', 'event.dataset': 'windows.security', 'event.code': 4624,
        'host.name': 'db-prod-07', 'user.name': 'svc_backup', 'source.ip': '203.0.113.44',
        'winlog.logon.type': 3, 'winlog.auth.package': 'NTLM', 'tenant.id': 't-default', 'agent.version': '8.14.2' } },
    { time: '00:39:02.771', sev: 'HIGH', dataset: 'windows.security', host: 'db-prod-07',
      summary: '4625 登录失败 · svc_backup · 状态 0xC000006D',
      tags: ['brute-force'],
      json: { '@timestamp': '2026-10-21T00:39:02.771Z', 'event.dataset': 'windows.security', 'event.code': 4625,
        'host.name': 'db-prod-07', 'user.name': 'svc_backup', 'source.ip': '203.0.113.44',
        'winlog.logon.failure.status': '0xC000006D', 'tenant.id': 't-default' } },
    { time: '00:38:47.003', sev: 'HIGH', dataset: 'windows.security', host: 'db-prod-07',
      summary: '4672 授予特殊权限 · svc_backup',
      tags: ['privilege'],
      json: { '@timestamp': '2026-10-21T00:38:47.003Z', 'event.dataset': 'windows.security', 'event.code': 4672,
        'host.name': 'db-prod-07', 'user.name': 'svc_backup', 'winlog.logon.id': '0x3e7',
        'tenant.id': 't-default' } },
    { time: '00:38:31.640', sev: 'MEDIUM', dataset: 'windows.security', host: 'db-prod-07',
      summary: '7045 服务安装 · UpdaterSvc · C:\\Windows\\Temp\\upd.exe',
      tags: ['persistence', 'T1543.003'],
      json: { '@timestamp': '2026-10-21T00:38:31.640Z', 'event.dataset': 'windows.security', 'event.code': 7045,
        'host.name': 'db-prod-07', 'service.name': 'UpdaterSvc',
        'service.image_path': 'C:\\\\Windows\\\\Temp\\\\upd.exe', 'tenant.id': 't-default' } },
    { time: '00:35:02.115', sev: 'MEDIUM', dataset: 'windows.security', host: 'db-prod-07',
      summary: '4625 登录失败 · 连续 219 次 · 源 203.0.113.44',
      tags: ['brute-force', 'T1110'],
      json: { '@timestamp': '2026-10-21T00:35:02.115Z', 'event.dataset': 'windows.security', 'event.code': 4625,
        'host.name': 'db-prod-07', 'user.name': 'svc_backup', 'source.ip': '203.0.113.44',
        'event.count': 219, 'tenant.id': 't-default' } },
    { time: '00:34:58.902', sev: 'MEDIUM', dataset: 'windows.security', host: 'file-srv-02',
      summary: '4688 进程创建 · vssadmin.exe delete shadows /all /quiet',
      tags: ['T1490', 'impact'],
      json: { '@timestamp': '2026-10-21T00:34:58.902Z', 'event.dataset': 'windows.security', 'event.code': 4688,
        'host.name': 'file-srv-02', 'process.name': 'vssadmin.exe',
        'process.command_line': 'vssadmin.exe delete shadows /all /quiet',
        'process.parent.name': 'cmd.exe', 'tenant.id': 't-default' } },
    { time: '00:34:20.400', sev: 'LOW', dataset: 'windows.security', host: 'file-srv-02',
      summary: '4663 对象访问 · D:\\share · 批量重命名',
      tags: ['impact'],
      json: { '@timestamp': '2026-10-21T00:34:20.400Z', 'event.dataset': 'windows.security', 'event.code': 4663,
        'host.name': 'file-srv-02', 'file.path': 'D:\\\\share', 'event.count': 1842, 'tenant.id': 't-default' } },
    { time: '00:30:11.087', sev: 'LOW', dataset: 'aws.cloudtrail', host: 'svc-deploy',
      summary: 'AssumeRole · role/deploy-admin · ap-southeast-1',
      tags: ['T1078.004', 'cloud'],
      json: { '@timestamp': '2026-10-21T00:30:11.087Z', 'event.dataset': 'aws.cloudtrail',
        'event.action': 'AssumeRole', 'user.name': 'svc-deploy@prod',
        'cloud.region': 'ap-southeast-1', 'source.ip': '203.0.113.44',
        'cloud.account.id': '4102-8837-1190', 'tenant.id': 't-default' } }
  ];

  const TREND_EVENTS = [420, 380, 460, 512, 470, 392, 341, 520, 684, 742, 821, 908, 872, 764, 690, 721, 858, 1041, 1182, 1094, 921, 782, 641, 522];
  const TREND_ALARMS = [8, 6, 9, 11, 9, 7, 5, 8, 12, 15, 18, 22, 26, 24, 19, 16, 20, 26, 31, 42, 46, 39, 28, 18];

  const ATTACK = [
    { tactic: '侦察', cells: [['T1595', 4], ['T1592', 2], ['T1590', 0]] },
    { tactic: '初始访问', cells: [['T1190', 9], ['T1078', 6], ['T1566', 2]] },
    { tactic: '执行', cells: [['T1059', 14], ['T1204', 5], ['T1047', 1]] },
    { tactic: '持久化', cells: [['T1543', 3], ['T1098', 2], ['T1136', 0]] },
    { tactic: '提权', cells: [['T1068', 1], ['T1548', 2], ['T1134', 0]] },
    { tactic: '防御规避', cells: [['T1490', 7], ['T1070', 4], ['T1027', 6]] },
    { tactic: '凭据访问', cells: [['T1110', 18], ['T1558', 5], ['T1003', 2]] },
    { tactic: '发现', cells: [['T1046', 6], ['T1087', 1], ['T1018', 2]] },
    { tactic: '横向移动', cells: [['T1021', 11], ['T1570', 1], ['T1550', 0]] },
    { tactic: '收集', cells: [['T1005', 1], ['T1560', 0], ['T1119', 0]] },
    { tactic: '命令控制', cells: [['T1071', 8], ['T1572', 3], ['T1105', 4]] },
    { tactic: '影响', cells: [['T1486', 6], ['T1490', 7], ['T1489', 1]] }
  ];

  const ENTITIES = [
    { name: 'db-prod-07', type: 'host', score: 94 },
    { name: 'file-srv-02', type: 'host', score: 91 },
    { name: 'svc-deploy@prod', type: 'identity', score: 86 },
    { name: 'ws-1042', type: 'host', score: 82 },
    { name: '203.0.113.44', type: 'ip', score: 76 }
  ];

  const SWATCHES = [
    ['--surface', 'surface', '基础卡片面'], ['--bg', 'bg', '应用底色'],
    ['--accent', 'accent', '唯一强调色'], ['--sev-critical', 'sev-critical', 'CRITICAL'],
    ['--sev-high', 'sev-high', 'HIGH'], ['--sev-medium', 'sev-medium', 'MEDIUM'],
    ['--sev-low', 'sev-low', 'LOW'], ['--sev-info', 'sev-info', 'INFO'],
    ['--text', 'text', '主文本'], ['--text-2', 'text-2', '次级文本'],
    ['--text-3', 'text-3', '辅助文本'], ['--border', 'border', '分隔线']
  ];

  const CONTRAST = [
    ['主文本 / 卡片面', '#14161a', '#ffffff', '18.11:1', 1],
    ['次级文本 / 卡片面', '#52545c', '#ffffff', '7.55:1', 1],
    ['辅助文本 / 应用底色', '#6a6c74', '#f4f4f1', '4.75:1', 1],
    ['强调色 / 卡片面', '#1e46d6', '#ffffff', '7.24:1', 1],
    ['白字 / 主按钮', '#ffffff', '#1e46d6', '7.24:1', 1],
    ['CRITICAL 徽标', '#c8102e', '#fdeef0', '5.23:1', 1],
    ['HIGH 徽标', '#c2410c', '#fdf0e8', '4.64:1', 1],
    ['MEDIUM 徽标', '#8a5a00', '#fdf6e3', '5.49:1', 1],
    ['深色主题 主文本', '#e8eaee', '#16191f', '14.61:1', 1],
    ['深色主题 强调填充', '#0f1115', '#8fa6ff', '8.16:1', 1]
  ];

  /* ------------------------------------------------------------- 2. Runtime */

  const state = {
    view: 'overview',
    alarms: ALARMS.slice(),
    sevFilter: new Set(),
    statusFilter: 'ACTIVE',
    savedView: 'ACTIVE',
    query: '',
    ownerFilter: '',
    minRisk: false,
    sortDesc: true,
    viewMode: 'list',
    selected: new Set(),
    openAlarmId: null,
    noteDirty: false,
    appliedQuery: $('#queryInput').value,
    draftQuery: $('#queryInput').value,
    pivots: [],
    paletteIndex: 0,
    paletteItems: [],
    lastFocus: null,
    freshSeconds: 12
  };

  const announce = (msg) => {
    const el = $('#toasts');
    const node = document.createElement('div');
    node.className = 'toast';
    node.innerHTML = '<svg width="14" height="14" aria-hidden="true"><use href="#i-check"/></svg><span>' + esc(msg) + '</span>';
    el.appendChild(node);
    setTimeout(() => {
      node.classList.add('is-out');
      setTimeout(() => node.remove(), reduceMotion() ? 0 : 220);
    }, 2600);
  };

  /* ------------------------------------------------------------ 3. Nav/view */

  function renderNav() {
    const nav = $('#railNav');
    nav.innerHTML = NAV.map((group) => {
      const items = group.items.map((item) => {
        const active = item.id === state.view;
        return '<a class="rail__link" href="#' + item.id + '" data-nav="' + item.id + '" data-label="' + esc(item.label) + '"' +
          (active ? ' aria-current="page"' : '') + '>' +
          '<svg width="16" height="16" aria-hidden="true"><use href="#' + item.icon + '"/></svg>' +
          '<span>' + esc(item.label) + '</span>' +
          (item.badge ? '<span class="rail__badge">' + item.badge + '</span>' : '') +
          '</a>';
      }).join('');
      return '<div class="rail__group"><p class="rail__group-label">' + esc(group.group) + '</p>' + items + '</div>';
    }).join('');

    nav.querySelectorAll('[data-nav]').forEach((link) => {
      link.addEventListener('click', (e) => {
        e.preventDefault();
        go(link.dataset.nav);
        if (window.matchMedia('(max-width: 860px)').matches) setRail('collapsed');
      });
    });
  }

  function go(id) {
    const isReal = ['overview', 'alarms', 'search', 'system'].includes(id);
    const view = isReal ? id : 'stub';
    state.view = id;

    $$('.view').forEach((section) => {
      const on = section.dataset.view === view;
      section.hidden = !on;
      section.classList.toggle('is-active', on);
      if (on && !reduceMotion()) {
        section.style.animation = 'none';
        void section.offsetWidth;
        section.style.animation = '';
      }
    });

    if (!isReal) buildStub(id);

    const meta = VIEW_META[id] || stubMeta(id);
    $('#crumbGroup').textContent = meta.group;
    $('#crumbPage').textContent = meta.title;
    document.title = meta.title + ' · SOCP 工作台';
    history.replaceState(null, '', '#' + id);

    renderNav();
    if (view === 'overview') requestAnimationFrame(renderCharts);
    window.scrollTo({ top: 0, behavior: reduceMotion() ? 'auto' : 'smooth' });
    $('#main').focus({ preventScroll: true });
  }

  function stubMeta(id) {
    for (const g of NAV) {
      const hit = g.items.find((i) => i.id === id);
      if (hit) return { group: g.group, title: hit.label };
    }
    return { group: '工作台', title: '模块' };
  }

  function buildStub(id) {
    const meta = stubMeta(id);
    const item = NAV.flatMap((g) => g.items).find((i) => i.id === id) || { icon: 'i-grid' };
    const stub = $('[data-view="stub"]');
    stub.innerHTML =
      '<div class="pagehead"><div class="pagehead__lead">' +
        '<p class="eyebrow">' + esc(meta.group) + ' · 模块</p>' +
        '<h2>' + esc(meta.title) + '</h2>' +
        '<p class="pagehead__sub">沿用统一的工作台外壳、栅格与表头规范，无需为新页面重新发明布局。</p>' +
      '</div>' +
      '<div class="pagehead__tools"><button class="btn btn--ghost" type="button" data-back>返回态势总览</button></div></div>' +
      '<div class="panel" style="padding:48px 32px;text-align:center">' +
        '<div class="empty__art" style="margin:0 auto 14px"><svg width="26" height="26" aria-hidden="true"><use href="#' + item.icon + '"/></svg></div>' +
        '<h3 style="font-size:16px;font-weight:600">' + esc(meta.title) + '</h3>' +
        '<p style="max-width:56ch;margin:8px auto 0;font-size:13px;color:var(--text-3);line-height:1.7">' +
          esc(STUB_BLURB[id] || '该模块沿用既有行为契约，本次交付聚焦框架与三个核心页面。') + '</p>' +
        '<p style="margin-top:16px;font-family:var(--font-mono);font-size:10.5px;color:var(--text-3)">' +
          'SCOPE · 框架已就绪，页面实现不在本次原型内</p>' +
      '</div>';
    stub.querySelector('[data-back]').addEventListener('click', () => go('overview'));
  }

  /* ------------------------------------------------------------ 4. Overview */

  function renderKpis() {
    const kpis = [
      { label: '待处置告警', value: '128', unit: '条', delta: '+18%', tone: 'bad', note: 'CRITICAL 12 · HIGH 34',
        spark: [22, 28, 25, 31, 29, 36, 33, 41, 38, 45, 42, 48], color: 'var(--sev-critical)' },
      { label: '平均确认时长', value: '6:42', unit: 'MTTA', delta: '−12%', tone: 'good', note: '目标 ≤ 10:00',
        spark: [8.2, 7.6, 7.9, 7.1, 6.8, 7.2, 6.6, 6.9, 6.4, 6.7, 6.3, 6.7], color: 'var(--accent)' },
      { label: '受影响资产', value: '34', unit: '台', delta: '+3', tone: 'bad', note: '含 2 台生产数据库',
        spark: [18, 21, 19, 24, 22, 27, 25, 29, 28, 31, 30, 34], color: 'var(--sev-high)' },
      { label: '24h 检测命中', value: '2,481', unit: '次', delta: '+6%', tone: 'flat', note: '39 条规则生效',
        spark: [1800, 2100, 1950, 2200, 2180, 2300, 2260, 2400, 2350, 2460, 2430, 2481], color: 'var(--text-3)' }
    ];

    $('#kpis').innerHTML = kpis.map((k) => (
      '<article class="kpi">' +
        '<p class="kpi__label">' + k.label + '</p>' +
        '<p class="kpi__value">' + k.value + '<small>' + k.unit + '</small></p>' +
        '<p class="kpi__meta"><span class="delta delta--' + k.tone + '">' + k.delta + '</span><span>' + k.note + '</span></p>' +
        '<div class="kpi__spark" data-spark="' + k.color + '" data-values="' + k.spark.join(',') + '"></div>' +
      '</article>'
    )).join('');
  }

  function sparkPath(width, height, values) {
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min || 1;
    return values.map((v, i) => {
      const x = (i / (values.length - 1)) * width;
      const y = height - 2 - ((v - min) / span) * (height - 5);
      return (i === 0 ? 'M' : 'L') + x.toFixed(1) + ' ' + y.toFixed(1);
    }).join(' ');
  }

  function renderSparks() {
    $$('[data-spark]').forEach((el) => {
      const values = el.dataset.values.split(',').map(Number);
      const w = el.clientWidth || 220;
      const h = 32;
      el.innerHTML = '<svg viewBox="0 0 ' + w + ' ' + h + '" width="100%" height="' + h + '" aria-hidden="true">' +
        '<path d="' + sparkPath(w, h, values) + ' L' + w + ' ' + h + ' L0 ' + h + ' Z" fill="' + el.dataset.spark + '" opacity="0.08"/>' +
        '<path d="' + sparkPath(w, h, values) + '" fill="none" stroke="' + el.dataset.spark + '" stroke-width="1.5" stroke-linejoin="round"/>' +
        '</svg>';
    });
  }

  function renderTrend() {
    const host = $('#trendChart');
    const w = Math.max(host.clientWidth - 74, 320);
    const h = 186;
    const maxE = Math.max(...TREND_EVENTS);
    const yMax = Math.ceil(maxE / 400) * 400;
    const maxA = Math.max(...TREND_ALARMS);
    const fmt = (v) => v >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(Math.round(v));
    const pt = (arr, max, i) => {
      const x = (i / (arr.length - 1)) * w;
      const y = h - 2 - (arr[i] / max) * (h - 14);
      return [x, y];
    };
    const line = (arr, max) => arr.map((_, i) => {
      const [x, y] = pt(arr, max, i);
      return (i === 0 ? 'M' : 'L') + x.toFixed(1) + ' ' + y.toFixed(1);
    }).join(' ');

    const evLine = line(TREND_EVENTS, yMax);
    const alLine = line(TREND_ALARMS, maxA);
    const hits = TREND_EVENTS.map((v, i) => {
      const x = (i / (TREND_EVENTS.length - 1)) * w;
      const bw = w / TREND_EVENTS.length;
      const label = String(i).padStart(2, '0') + ':00 · 事件 ' + v + ' · 告警 ' + TREND_ALARMS[i];
      return '<rect class="chart__hit" x="' + (x - bw / 2).toFixed(1) + '" y="0" width="' + bw.toFixed(1) + '" height="' + h + '" fill="transparent"><title>' + label + '</title></rect>';
    }).join('');

    host.innerHTML =
      '<div class="chart__wrap">' +
        '<div class="chart__y"><span>' + fmt(yMax) + '</span><span>' + fmt(yMax * 2 / 3) + '</span><span>' + fmt(yMax / 3) + '</span><span>0</span></div>' +
        '<div class="chart__plot">' +
          '<div class="chart__grid" aria-hidden="true"><span></span><span></span><span></span><span></span></div>' +
          '<svg viewBox="0 0 ' + w + ' ' + h + '" width="100%" height="' + h + '" role="img" aria-label="24 小时事件与告警趋势">' +
            '<defs><linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">' +
              '<stop offset="0%" stop-color="var(--accent)" stop-opacity="0.26"/>' +
              '<stop offset="100%" stop-color="var(--accent)" stop-opacity="0"/>' +
            '</linearGradient></defs>' +
            '<path d="' + evLine + ' L' + w + ' ' + h + ' L0 ' + h + ' Z" fill="url(#trendFill)"/>' +
            '<path d="' + evLine + '" fill="none" stroke="var(--accent)" stroke-width="1.6" stroke-linejoin="round"/>' +
            '<path d="' + alLine + '" fill="none" stroke="var(--sev-critical)" stroke-width="1.4" stroke-dasharray="4 3" opacity="0.85"/>' +
            '<circle cx="' + pt(TREND_ALARMS, maxA, TREND_ALARMS.length - 1)[0].toFixed(1) + '" cy="' +
              pt(TREND_ALARMS, maxA, TREND_ALARMS.length - 1)[1].toFixed(1) + '" r="3" class="chart__marker"/>' +
            hits +
          '</svg>' +
        '</div>' +
      '</div>' +
      '<div class="chart__x"><span>00:00</span><span>04:00</span><span>08:00</span><span>12:00</span><span>16:00</span><span>20:00</span><span>现在</span></div>';
  }

  function renderDist() {
    const rows = [
      ['CRITICAL', 12, 'var(--fill-critical)'], ['HIGH', 34, 'var(--fill-high)'],
      ['MEDIUM', 47, 'var(--fill-medium)'], ['LOW', 29, 'var(--fill-low)'], ['INFO', 6, 'var(--fill-neutral)']
    ];
    const total = rows.reduce((a, r) => a + r[1], 0);
    $('#sevDist').innerHTML = rows.map(([name, n, color]) => {
      const pct = Math.round((n / total) * 100);
      return '<div class="dist__row">' +
        '<span class="dist__name" style="color:var(--sev-' + name.toLowerCase() + ')">' + name + '</span>' +
        '<span class="dist__track"><span class="dist__fill" style="width:' + pct + '%;background:' + color + '"></span></span>' +
        '<span class="dist__num">' + n + '</span>' +
        '<span class="dist__pct">' + pct + '%</span>' +
      '</div>';
    }).join('');
  }

  function renderAttack() {
    const head = ATTACK.map((t) => '<div class="attack__tactic">' + t.tactic + '</div>').join('');
    const body = ATTACK.map((t) => t.cells.map(([id, v]) => {
      const level = v === 0 ? 0 : v < 3 ? 1 : v < 7 ? 2 : 3;
      return '<div class="attack__cell" data-level="' + level + '" title="' + id + ' · 命中 ' + v + ' 次">' +
        (v === 0 ? '·' : v) + '</div>';
    }).join('')).join('');
    $('#attackMatrix').innerHTML =
      '<div class="attack__grid">' + head + body + '</div>' +
      '<div class="attack__legend"><span>少</span>' +
        '<i style="background:var(--surface-3)"></i>' +
        '<i style="background:color-mix(in srgb, var(--accent) 18%, var(--surface))"></i>' +
        '<i style="background:color-mix(in srgb, var(--accent) 42%, var(--surface))"></i>' +
        '<i style="background:var(--accent)"></i><span>多</span></div>';
  }

  function renderEntities() {
    $('#topEntities').innerHTML = ENTITIES.map((e) => (
      '<li><span class="entity__name"><svg width="13" height="13" aria-hidden="true"><use href="#i-' +
        (e.type === 'host' ? 'server' : e.type === 'identity' ? 'user' : 'radar') + '"/></svg>' + esc(e.name) +
        '<span class="entity__type">' + e.type + '</span></span>' +
        '<span class="entity__bar"><i style="width:' + e.score + '%"></i></span>' +
        '<span class="entity__score">' + e.score + '</span></li>'
    )).join('');
  }

  function sevBadge(sev) {
    return '<span class="sev sev--' + sev.toLowerCase() + '"><i aria-hidden="true"></i>' + SEV_META[sev].label + '</span>';
  }

  function alarmRow(a, idx) {
    const owner = a.owner
      ? '<span class="owner"><span class="avatar">' + esc(a.owner.slice(0, 1)) + '</span>' + esc(a.owner) + '</span>'
      : '<span class="owner owner--none">未分配</span>';
    const checked = state.selected.has(a.id) ? ' checked' : '';
    return '<tr data-id="' + a.id + '" data-sev="' + a.sev + '">' +
      '<td class="col-check"><label class="checkbox"><input type="checkbox" data-row-check="' + a.id + '" aria-label="选择告警 ' + a.id + '"' + checked + '><span></span></label></td>' +
      '<td class="col-sev">' + sevBadge(a.sev) + '</td>' +
      '<td><button class="alarm__title" type="button" data-open="' + a.id + '">' + esc(a.title) + '</button>' +
        '<span class="alarm__id">' + a.id + ' · ' + esc(a.rule) + '</span></td>' +
      '<td><span class="entity-cell"><b>' + esc(a.entity) + '</b><span>' + a.etype + '</span></span></td>' +
      '<td class="col-risk"><span class="risk' + (a.risk >= 80 ? ' risk--hot' : '') + '">' + a.risk + '</span></td>' +
      '<td><span class="status status--' + (STATUS_CLASS[a.status] || 'closed') + '">' + a.status + '</span></td>' +
      '<td>' + owner + '</td>' +
      '<td class="col-time"><span class="time" title="' + a.ago + '">' + a.time + '</span></td>' +
      '<td class="col-act"><button class="iconbtn iconbtn--sm" type="button" data-open="' + a.id + '" aria-label="打开 ' + a.id + ' 详情">' +
        '<svg width="14" height="14" aria-hidden="true"><use href="#i-more"/></svg></button></td>' +
    '</tr>';
  }

  function renderRecent() {
    const rows = ALARMS.slice(0, 5);
    $('#recentAlarms').innerHTML =
      '<table class="table"><caption class="sr-only">最近五条告警</caption><thead><tr>' +
        '<th class="col-sev">严重</th><th>告警</th><th>实体</th><th>状态</th><th class="col-time">时间</th>' +
      '</tr></thead><tbody>' +
      rows.map((a) => '<tr data-sev="' + a.sev + '">' +
        '<td class="col-sev">' + sevBadge(a.sev) + '</td>' +
        '<td><button class="alarm__title" type="button" data-open="' + a.id + '">' + esc(a.title) + '</button></td>' +
        '<td><span class="entity-cell"><b>' + esc(a.entity) + '</b></span></td>' +
        '<td><span class="status status--' + (STATUS_CLASS[a.status] || 'closed') + '">' + a.status + '</span></td>' +
        '<td class="col-time"><span class="time">' + a.time + '</span></td>' +
      '</tr>').join('') + '</tbody></table>';

    $$('#recentAlarms [data-open]').forEach((b) => b.addEventListener('click', () => openDrawer(b.dataset.open)));
  }

  function renderCharts() {
    renderSparks();
    renderTrend();
    renderDist();
    renderAttack();
    renderEntities();
  }

  /* -------------------------------------------------------------- 5. Alarms */

  function filteredAlarms() {
    const q = state.query.trim().toLowerCase();
    const rows = state.alarms.filter((a) => {
      if (state.sevFilter.size && !state.sevFilter.has(a.sev)) return false;
      if (state.statusFilter === 'ACTIVE' && !['OPEN', 'INVESTIGATING'].includes(a.status)) return false;
      if (state.statusFilter === 'mine' && a.owner !== '林可') return false;
      if (!['ACTIVE', 'ALL', 'mine'].includes(state.statusFilter) && a.status !== state.statusFilter) return false;
      if (state.ownerFilter === 'unassigned' && a.owner) return false;
      if (state.ownerFilter && state.ownerFilter !== 'unassigned' && a.owner !== state.ownerFilter) return false;
      if (state.minRisk && a.risk < 80) return false;
      if (q) {
        const hay = (a.title + ' ' + a.rule + ' ' + a.entity + ' ' + a.id).toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
    // 夹具本身按发生时间倒序，用原始下标排序可避免跨午夜的时间字符串误判
    const order = new Map(state.alarms.map((a, i) => [a.id, i]));
    rows.sort((a, b) => state.sortDesc ? order.get(a.id) - order.get(b.id) : order.get(b.id) - order.get(a.id));
    return rows;
  }

  function renderAlarms() {
    const rows = filteredAlarms();
    const narrowed = state.sevFilter.size > 0 || state.query.trim().length > 0 ||
      state.ownerFilter !== '' || state.minRisk;
    const mineTotal = state.alarms.filter((a) => a.owner === '林可').length;
    const scopeTotal = { ACTIVE: 128, ALL: 1406, mine: mineTotal };
    const total = narrowed ? rows.length : (scopeTotal[state.statusFilter] ?? rows.length);

    $('#alarmBody').innerHTML = rows.map(alarmRow).join('');
    $('#alarmCount').textContent = String(total);
    $('#pageShown').textContent = String(rows.length);
    $('#pagerMeta').innerHTML = '第 1–' + rows.length + ' 条，共 <b>' + total + '</b> 条' +
      (narrowed ? ' <span class="muted">· 已按筛选缩小范围</span>' : ' <span class="muted">· 示例仅载入首页</span>');
    $('#pagerBtns').hidden = total <= rows.length;
    $('#alarmEmpty').hidden = rows.length > 0;
    $('#alarmTable').hidden = rows.length === 0;
    updateSelectionUi();

    $$('#alarmBody [data-open]').forEach((b) => {
      b.addEventListener('click', (e) => { e.stopPropagation(); openDrawer(b.dataset.open); });
    });
    $$('#alarmBody [data-row-check]').forEach((cb) => {
      cb.addEventListener('change', () => {
        if (cb.checked) state.selected.add(cb.dataset.rowCheck);
        else state.selected.delete(cb.dataset.rowCheck);
        updateSelectionUi();
        syncCheckAll();
      });
    });
    $$('#alarmBody tr').forEach((tr) => {
      tr.addEventListener('mouseenter', () => tr.classList.add('is-rowhover'));
      tr.addEventListener('mouseleave', () => tr.classList.remove('is-rowhover'));
      tr.addEventListener('click', (e) => {
        if (e.target.closest('[data-row-check]') || e.target.closest('label')) return;
        openDrawer(tr.dataset.id);
      });
    });
    syncCheckAll();
  }

  function updateSelectionUi() {
    $('#selCount').textContent = String(state.selected.size);
    $('#claimTop').disabled = state.selected.size === 0;
  }

  function syncCheckAll() {
    const boxes = $$('#alarmBody [data-row-check]');
    const checked = boxes.filter((b) => b.checked).length;
    const all = $('#checkAll');
    all.checked = boxes.length > 0 && checked === boxes.length;
    all.indeterminate = checked > 0 && checked < boxes.length;
  }

  function renderSevChips() {
    const defs = [['CRITICAL', '严重'], ['HIGH', '高'], ['MEDIUM', '中'], ['LOW', '低'], ['INFO', '提示']];
    $('#sevChips').innerHTML = defs.map(([id, label]) => (
      '<button class="chip' + (state.sevFilter.has(id) ? ' is-on' : '') + '" type="button" data-sev-chip="' + id + '" aria-pressed="' + state.sevFilter.has(id) + '">' +
        '<i style="background:var(--sev-' + id.toLowerCase() + ')" aria-hidden="true"></i>' + label + '</button>'
    )).join('');
    $$('[data-sev-chip]').forEach((b) => {
      b.addEventListener('click', () => {
        const id = b.dataset.sevChip;
        if (state.sevFilter.has(id)) state.sevFilter.delete(id);
        else state.sevFilter.add(id);
        renderSevChips();
        renderAlarms();
      });
    });
  }

  function setSavedView(kind) {
    state.statusFilter = kind;
    state.savedView = kind;
    $$('.savedviews__tab').forEach((t) => {
      t.setAttribute('aria-selected', String(t.dataset.viewFilter === kind));
    });
    const sel = $('#statusFilter');
    if ([...sel.options].some((o) => o.value === kind)) sel.value = kind;
    renderAlarms();
  }

  /* -------------------------------------------------------------- 6. Drawer */

  function openDrawer(id) {
    const a = state.alarms.find((x) => x.id === id);
    if (!a) return;
    state.openAlarmId = id;
    state.noteDirty = false;
    state.lastFocus = document.activeElement;

    $('#drawerId').textContent = a.id;
    $('#drawerTitle').textContent = a.title;
    $('#drawerBody').innerHTML =
      '<div class="dsec">' +
        '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">' + sevBadge(a.sev) +
          '<span class="status status--' + (STATUS_CLASS[a.status] || 'closed') + '">' + a.status + '</span>' +
          '<span class="tag">' + esc(a.mitre) + '</span>' +
          '<span class="tag">风险 ' + a.risk + '</span>' +
          '<span class="tag">' + a.ago + '</span>' +
        '</div>' +
        '<p style="margin-top:10px;font-size:12px;line-height:1.65;color:var(--text-2)">' + esc(a.desc) + '</p>' +
      '</div>' +
      '<div class="dsec">' +
        '<p class="dsec__title">处置流转</p>' +
        '<div class="flow">' +
          ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED'].map((s, i) =>
            '<span class="flow__step' + (s === a.status ? ' is-now' : '') + '">' + s + '</span>' +
            (i < 3 ? '<span class="flow__arrow" aria-hidden="true">→</span>' : '')
          ).join('') +
        '</div>' +
        '<p style="margin-top:6px;font-size:10.5px;color:var(--text-3)">可选迁移由服务端 allowedTransitions 决定；冲突保留原因并刷新当前状态。</p>' +
      '</div>' +
      '<div class="dsec">' +
        '<p class="dsec__title">关键字段</p>' +
        '<dl class="dmeta">' +
          '<dt>实体</dt><dd>' + esc(a.entity) + ' <span class="tag">' + a.etype + '</span></dd>' +
          '<dt>检测规则</dt><dd class="mono">' + esc(a.rule) + '</dd>' +
          '<dt>ATT&amp;CK</dt><dd class="mono">' + esc(a.mitre) + '</dd>' +
          '<dt>负责人</dt><dd>' + (a.owner ? esc(a.owner) : '<span class="muted">未分配</span>') + '</dd>' +
          '<dt>租户</dt><dd class="mono">t-default</dd>' +
        '</dl>' +
      '</div>' +
      '<div class="dsec">' +
        '<p class="dsec__title">证据时间线</p>' +
        '<div class="timeline">' + a.timeline.map((t) => (
          '<div class="tl" data-tone="' + t.tone + '">' +
            '<span class="tl__time">' + t.t + '</span>' +
            '<span class="tl__rail"><i class="tl__dot"></i></span>' +
            '<div class="tl__body"><p class="tl__title">' + esc(t.title) + '</p>' +
              '<p class="tl__desc">' + esc(t.desc) + '</p>' +
              (t.code ? '<pre class="tl__code">' + esc(t.code) + '</pre>' : '') +
            '</div>' +
          '</div>'
        )).join('') + '</div>' +
      '</div>' +
      '<div class="dsec">' +
        '<p class="dsec__title">研判备注</p>' +
        '<div class="note">' +
          '<label for="noteInput">仅本租户可见 · 关闭前需保存或放弃</label>' +
          '<textarea id="noteInput" placeholder="记录推理过程、已排除项与下一步动作…"></textarea>' +
          '<p class="note__warn" id="noteWarn">备注尚未保存，已阻止关闭。请保存或清空后再退出。</p>' +
        '</div>' +
      '</div>';

    const note = $('#noteInput');
    note.addEventListener('input', () => { state.noteDirty = note.value.trim().length > 0; });

    const drawer = $('#drawer');
    drawer.hidden = false;
    $('#drawerScrim').hidden = false;
    void drawer.offsetWidth; /* 先完成一次布局，过渡才能从隐藏态起算 */
    requestAnimationFrame(() => {
      drawer.classList.add('is-open');
      $('#drawerScrim').classList.add('is-open');
    });
    $('#drawerClose').focus();
  }

  function closeDrawer(force) {
    if (state.noteDirty && !force) {
      $('#noteWarn')?.classList.add('is-on');
      announce('备注未保存，已阻止关闭');
      $('#noteInput')?.focus();
      return;
    }
    const drawer = $('#drawer');
    drawer.classList.remove('is-open');
    $('#drawerScrim').classList.remove('is-open');
    const hide = () => { drawer.hidden = true; $('#drawerScrim').hidden = true; };
    if (reduceMotion()) hide(); else setTimeout(hide, 280);
    state.openAlarmId = null;
    state.noteDirty = false;
    const back = state.lastFocus;
    if (back && document.contains(back) && typeof back.focus === 'function') back.focus();
    else $('#main').focus({ preventScroll: true });
  }

  function trapFocus(e, container) {
    const focusables = $$('a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])', container)
      .filter((el) => el.offsetParent !== null);
    if (!focusables.length) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }

  /* -------------------------------------------------------------- 7. Search */

  function renderFields() {
    $('#fieldList').innerHTML = FIELDS.map(([name, count]) =>
      '<li data-field="' + esc(name) + '"><span>' + esc(name) + '</span><b>' + count + '</b></li>'
    ).join('');
    $$('#fieldList li').forEach((li) => {
      li.addEventListener('click', () => {
        $('#queryInput').value += ' | stats count by ' + li.dataset.field;
        syncDraft();
        $('#queryInput').focus();
      });
    });
  }

  function renderHisto() {
    const values = [3, 5, 4, 7, 6, 9, 12, 10, 8, 11, 15, 18, 14, 12, 9, 7, 11, 16, 22, 19, 14, 10, 8, 6,
                    9, 13, 17, 21, 18, 12, 8, 6, 5, 7, 10, 14, 11, 8, 6, 4, 5, 7, 9, 12, 10, 7, 5, 3];
    const max = Math.max(...values);
    $('#histo').innerHTML = values.map((v) =>
      '<i class="histo__bar" style="height:' + Math.max(8, (v / max) * 100) + '%" title="' + v + ' 条事件"></i>'
    ).join('');
  }

  function jsonBlock(obj) {
    const raw = JSON.stringify(obj, null, 2);
    return esc(raw)
      .replace(/"([^"]+)":/g, '<span class="k">"$1"</span>:')
      .replace(/: "([^"]*)"/g, ': <span class="s">"$1"</span>');
  }

  function renderEvents() {
    const q = state.appliedQuery.toLowerCase();
    let rows = EVENTS.slice();
    if (state.pivots.length) {
      rows = rows.filter((e) => state.pivots.every((p) => String(e.json[p.field] ?? '').includes(p.value) || e.summary.includes(p.value)));
    }
    const noMatch = q.includes('no-match') || q.includes('nothing');
    $('#eventCount').textContent = noMatch ? '0' : rows.length === EVENTS.length ? '1,284' : String(rows.length);
    $('#searchMeta').textContent = noMatch ? '耗时 0.31s · 扫描 12.4M 条 · 已应用查询' : '耗时 0.42s · 扫描 12.4M 条 · 已应用查询';
    $('#events').hidden = noMatch || rows.length === 0;
    $('#searchEmpty').hidden = !(noMatch || rows.length === 0);
    $('#searchPager').hidden = noMatch || rows.length === 0;
    if (noMatch || rows.length === 0) { $('#emptyQuery').textContent = state.appliedQuery; return; }
    $('#searchPagerMeta').innerHTML = '第 1–' + rows.length + ' 条，共 <b>' +
      (state.pivots.length ? rows.length : '1,284') + '</b> 条' +
      (state.pivots.length ? ' <span class="muted">· 已按字段过滤 · 示例仅载入首页</span>' : ' <span class="muted">· 已应用查询 · 示例仅载入首页</span>');

    if (state.viewMode === 'table') {
      $('#events').innerHTML =
        '<table class="table table--events"><caption class="sr-only">事件表格视图，按时间倒序</caption>' +
          '<thead><tr><th class="col-time">时间</th><th class="col-sev">级别</th><th>数据集</th><th>主机</th><th>摘要</th></tr></thead><tbody>' +
          rows.map((e) => {
            const hostName = e.json['host.name'] || e.json['user.name'] || e.dataset;
            return '<tr data-sev="' + e.sev + '">' +
              '<td class="col-time"><span class="time">' + e.time + '</span></td>' +
              '<td class="col-sev">' + sevBadge(e.sev) + '</td>' +
              '<td><span class="tag">' + esc(e.dataset) + '</span></td>' +
              '<td><b class="mono">' + esc(hostName) + '</b></td>' +
              '<td><code>' + esc(e.summary) + '</code></td>' +
            '</tr>';
          }).join('') +
        '</tbody></table>';
      return;
    }

    $('#events').innerHTML = rows.map((e, i) => {
      const hostName = e.json['host.name'] || e.json['user.name'] || '';
      const srcIp = e.json['source.ip'] || '';
      return '<article class="evt" data-evt="' + i + '">' +
        '<button class="evt__head" type="button" aria-expanded="false">' +
          '<svg class="evt__toggle" width="12" height="12" aria-hidden="true"><use href="#i-chevron"/></svg>' +
          '<span class="evt__time">' + e.time + '</span>' +
          sevBadge(e.sev) +
          '<span class="evt__summary"><code>' + esc(e.summary) + '</code></span>' +
          '<span class="evt__tags">' + e.tags.map((t) => '<span class="tag">' + esc(t) + '</span>').join('') + '</span>' +
        '</button>' +
        '<div class="evt__body">' +
          '<pre class="json">' + jsonBlock(e.json) + '</pre>' +
          '<div class="evt__actions">' +
            (hostName ? '<button class="btn btn--quiet btn--sm" type="button" data-pivot="' + esc(hostName) + '" data-field="host.name">按主机过滤</button>' : '') +
            (srcIp ? '<button class="btn btn--quiet btn--sm" type="button" data-pivot="' + esc(srcIp) + '" data-field="source.ip">按源 IP 过滤</button>' : '') +
            '<button class="btn btn--quiet btn--sm" type="button" data-copy>复制字段</button>' +
          '</div>' +
        '</div>' +
      '</article>';
    }).join('');

    $$('#events .evt__head').forEach((head) => {
      head.addEventListener('click', () => {
        const card = head.closest('.evt');
        const open = card.classList.toggle('is-open');
        head.setAttribute('aria-expanded', String(open));
      });
    });
    $$('#events [data-pivot]').forEach((b) => {
      b.addEventListener('click', () => {
        if (!b.dataset.pivot) return;
        addPivot(b.dataset.field, b.dataset.pivot);
      });
    });
    $$('#events [data-copy]').forEach((b) => b.addEventListener('click', () => announce('字段已复制到剪贴板')));
  }

  function renderPivots() {
    const box = $('#pivots');
    box.hidden = state.pivots.length === 0;
    box.innerHTML = state.pivots.map((p, i) =>
      '<span class="chip is-on"><span class="mono">' + esc(p.field) + '</span> = ' + esc(p.value) +
      '<button class="chip__x" type="button" data-rm-pivot="' + i + '" aria-label="移除过滤 ' + esc(p.value) + '">' +
      '<svg width="10" height="10" aria-hidden="true"><use href="#i-close"/></svg></button></span>'
    ).join('');
    $$('[data-rm-pivot]').forEach((b) => b.addEventListener('click', () => {
      state.pivots.splice(Number(b.dataset.rmPivot), 1);
      renderPivots();
      renderEvents();
    }));
  }

  function addPivot(field, value) {
    if (!value) return;
    if (state.pivots.some((p) => p.field === field && p.value === value)) return;
    state.pivots.push({ field, value });
    renderPivots();
    renderEvents();
    announce('已按 ' + field + ' = ' + value + ' 过滤');
  }

  function syncDraft() {
    state.draftQuery = $('#queryInput').value;
    $('#unapplied').hidden = state.draftQuery === state.appliedQuery;
  }

  function runSearch() {
    state.appliedQuery = state.draftQuery;
    $('#unapplied').hidden = true;
    const box = $('#events');
    box.hidden = false;
    $('#searchEmpty').hidden = true;
    box.innerHTML = '<div style="padding:14px 12px">' +
      '<div class="skel skel--title" style="margin-bottom:10px"></div>' +
      '<div class="skel skel--row" style="margin-bottom:8px"></div>' +
      '<div class="skel skel--row skel--short" style="margin-bottom:8px"></div>' +
      '<div class="skel skel--row"></div></div>';
    $('#searchMeta').textContent = '检索中…';
    const delay = reduceMotion() ? 60 : 480;
    setTimeout(() => { renderEvents(); announce('查询已应用'); }, delay);
  }

  /* ------------------------------------------------------------- 8. Palette */

  function paletteSource() {
    const pages = NAV.flatMap((g) => g.items.map((i) => ({ label: i.label, group: g.group, icon: i.icon, view: i.id })));
    const ents = ENTITIES.map((e) => ({ label: e.name, group: '实体', icon: e.type === 'host' ? 'i-server' : 'i-radar', entity: e.name }));
    const acts = [
      { label: '认领选中告警', group: '操作', icon: 'i-user', action: 'claim' },
      { label: '导出当前队列为 CSV', group: '操作', icon: 'i-download', action: 'export' },
      { label: '切换明暗主题', group: '操作', icon: 'i-palette', action: 'theme' },
      { label: '主题：恢复跟随系统', group: '操作', icon: 'i-sun', action: 'theme-system' }
    ];
    return [...pages, ...ents, ...acts];
  }

  function openPalette() {
    state.lastFocus = document.activeElement;
    const p = $('#palette');
    p.hidden = false;
    $('#paletteScrim').hidden = false;
    $('#cmdOpen').setAttribute('aria-expanded', 'true');
    void p.offsetWidth;
    requestAnimationFrame(() => {
      p.classList.add('is-open');
      $('#paletteScrim').classList.add('is-open');
    });
    $('#paletteInput').value = '';
    renderPalette('');
    $('#paletteInput').focus();
  }

  function closePalette() {
    const p = $('#palette');
    p.classList.remove('is-open');
    $('#paletteScrim').classList.remove('is-open');
    $('#cmdOpen').setAttribute('aria-expanded', 'false');
    const hide = () => { p.hidden = true; $('#paletteScrim').hidden = true; };
    if (reduceMotion()) hide(); else setTimeout(hide, 180);
    const back = state.lastFocus;
    if (back && document.contains(back) && typeof back.focus === 'function') back.focus();
  }

  function renderPalette(query) {
    const q = query.trim().toLowerCase();
    const src = paletteSource();
    const items = q ? src.filter((i) => (i.label + ' ' + i.group).toLowerCase().includes(q)) : src.slice(0, 12);
    state.paletteItems = items;
    state.paletteIndex = 0;

    if (!items.length) {
      $('#paletteList').innerHTML = '<p class="palette__empty">没有匹配项。<br>试试模块名、主机名或源 IP。</p>';
      $('#paletteInput').setAttribute('aria-activedescendant', '');
      return;
    }

    let lastGroup = '';
    const html = items.map((item, i) => {
      let head = '';
      if (item.group !== lastGroup) {
        head = '<p class="palette__group">' + esc(item.group) + '</p>';
        lastGroup = item.group;
      }
      const label = q
        ? item.label.replace(new RegExp('(' + q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig'), '<mark>$1</mark>')
        : esc(item.label);
      return head + '<button class="palette__item" type="button" role="option" id="pal-opt-' + i + '" aria-selected="' + (i === 0) + '" data-pal="' + i + '">' +
        '<svg width="15" height="15" aria-hidden="true"><use href="#' + item.icon + '"/></svg>' +
        '<span>' + label + '</span>' +
        '<span class="palette__meta">' + esc(item.group) + '</span>' +
      '</button>';
    }).join('');

    $('#paletteList').innerHTML = html;
    $('#paletteInput').setAttribute('aria-activedescendant', 'pal-opt-0');
    $$('#paletteList [data-pal]').forEach((b) => {
      b.addEventListener('click', () => activatePalette(Number(b.dataset.pal)));
      b.addEventListener('mousemove', () => setPaletteIndex(Number(b.dataset.pal)));
    });
  }

  function setPaletteIndex(i) {
    const items = $$('#paletteList [data-pal]');
    if (!items.length) return;
    state.paletteIndex = Math.max(0, Math.min(i, items.length - 1));
    items.forEach((el, idx) => el.setAttribute('aria-selected', String(idx === state.paletteIndex)));
    $('#paletteInput').setAttribute('aria-activedescendant', 'pal-opt-' + state.paletteIndex);
    items[state.paletteIndex].scrollIntoView({ block: 'nearest' });
  }

  function activatePalette(i) {
    const item = state.paletteItems[i];
    if (!item) return;
    closePalette();
    if (item.view) { go(item.view); return; }
    if (item.entity) { go('search'); addPivot('host.name', item.entity); return; }
    if (item.action === 'theme') { toggleTheme(); return; }
    if (item.action === 'theme-system') { followSystemTheme(); return; }
    if (item.action === 'claim') { claimSelected(); return; }
    if (item.action === 'export') { announce('已提交导出任务，完成后可在报表下载'); return; }
  }

  function claimSelected() {
    if (!state.selected.size) { announce('请先选择至少一条告警'); return; }
    const count = state.selected.size;
    state.selected.forEach((id) => {
      const a = state.alarms.find((x) => x.id === id);
      if (a && !a.owner) { a.owner = '林可'; if (a.status === 'OPEN') a.status = 'INVESTIGATING'; }
    });
    state.selected.clear();
    renderAlarms();
    announce('已认领 ' + count + ' 条告警');
  }

  /* --------------------------------------------------------------- 9. Theme */

  function applyTheme(theme, persist = true) {
    document.documentElement.dataset.theme = theme;
    if (persist) {
      try { localStorage.setItem('socp.theme', theme); } catch (e) { /* ignore */ }
    }
    const btn = $('#themeBtn');
    btn.setAttribute('aria-pressed', String(theme === 'dark'));
    btn.title = persist ? '切换明暗主题' : '切换明暗主题（当前跟随系统）';
    if (state.view === 'overview') requestAnimationFrame(renderCharts);
    if (state.view === 'system') renderSystem();
  }

  const systemTheme = () => window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  const savedTheme = () => { try { return localStorage.getItem('socp.theme'); } catch (e) { return null; } };

  function toggleTheme() {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    announce(next === 'dark' ? '已切换到深色主题，已记住选择' : '已切换到浅色主题，已记住选择');
  }

  function followSystemTheme() {
    try { localStorage.removeItem('socp.theme'); } catch (e) { /* ignore */ }
    applyTheme(systemTheme(), false);
    announce('已恢复跟随系统主题');
  }

  /* --------------------------------------------------- 10. Design system view */

  function renderSystem() {
    const styles = getComputedStyle(document.documentElement);
    $('#swatches').innerHTML = SWATCHES.map(([token, key, label]) => {
      const value = styles.getPropertyValue(token).trim();
      return '<div class="swatch"><div class="swatch__chip" style="background:' + value + '"></div>' +
        '<div class="swatch__meta"><b>' + label + '</b><span>' + esc(value) + '</span></div></div>';
    }).join('');

    $('#contrastBody').innerHTML = CONTRAST.map(([name, fg, bg, ratio, pass]) =>
      '<tr><td>' + name + '</td>' +
      '<td class="mono" style="font-size:11px">' + fg + '</td>' +
      '<td class="mono" style="font-size:11px">' + bg + '</td>' +
      '<td class="mono">' + ratio + '</td>' +
      '<td><span class="status status--res">AA 通过</span></td></tr>'
    ).join('');

    const type = [
      ['26 / 600', '态势总览', '26px', 600], ['20 / 600', '告警队列', '20px', 600],
      ['16 / 600', 'SMB 暴力破解后成功登录', '16px', 600],
      ['14 / 400', '四类数据源独立刷新，最新时间取各源最旧的成功时间。', '14px', 400],
      ['13 / 400', '默认进入待处置范围，避免新噪声掩埋未认领工作。', '13px', 400],
      ['11 / 500', 'SEVERITY · STATUS · OWNER', '11px', 500],
      ['10.5 mono', 'ALM-2026-1042 · 00:39:12.418', '10.5px', 400]
    ];
    $('#typeScale').innerHTML = type.map(([tag, sample, size, weight]) =>
      '<div class="type__row"><span class="type__tag">' + tag + '</span>' +
      '<span class="type__sample" style="font-size:' + size + ';font-weight:' + weight + (tag.includes('mono') ? ';font-family:var(--font-mono)' : '') + '">' +
      sample + '</span></div>'
    ).join('');

    const spaces = [2, 4, 6, 8, 12, 16, 20, 24, 32];
    $('#spaceScale').innerHTML = spaces.map((s) =>
      '<div class="scale__row"><span class="scale__label">' + s + '</span>' +
      '<span class="scale__track"><span class="scale__bar" style="width:' + Math.round((s / 32) * 100) + '%"></span></span>' +
      '<span class="scale__val">' + s + 'px</span></div>'
    ).join('');
    $('#radiusRow').innerHTML = [['4', 'var(--r-sm)'], ['6', 'var(--r-md)'], ['10', 'var(--r-lg)'], ['12', 'var(--r-xl)'], ['999', 'var(--r-full)']]
      .map(([label, r]) => '<div><i style="border-radius:' + r + '"></i><span>' + label + '</span></div>').join('');
  }

  /* ------------------------------------------------------------ 11. Wiring */

  function setRail(mode) {
    document.body.dataset.rail = mode;
    $('#railToggle').setAttribute('aria-expanded', String(mode === 'expanded'));
    $('#railToggle').setAttribute('aria-label', mode === 'expanded' ? '折叠导航' : '展开导航');
    try { localStorage.setItem('socp.rail', mode); } catch (e) { /* ignore */ }
  }

  function init() {
    renderNav();
    renderKpis();
    renderSevChips();
    renderAlarms();
    renderRecent();
    renderFields();
    renderHisto();
    renderPivots();
    renderEvents();
    renderSystem();
    renderCharts();

    const hash = location.hash.replace('#', '');
    if (hash) go(hash);

    $('#themeBtn').addEventListener('click', toggleTheme);
    $('#themeBtn').setAttribute('aria-pressed', String(document.documentElement.dataset.theme === 'dark'));
    $('#dsTheme').addEventListener('click', toggleTheme);

    // 未显式选择过主题时，系统偏好变化实时跟随
    const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const onSystemTheme = (e) => { if (!savedTheme()) applyTheme(e.matches ? 'dark' : 'light', false); };
    if (darkQuery.addEventListener) darkQuery.addEventListener('change', onSystemTheme);
    else if (darkQuery.addListener) darkQuery.addListener(onSystemTheme);
    $('#railToggle').addEventListener('click', () => setRail(document.body.dataset.rail === 'expanded' ? 'collapsed' : 'expanded'));
    $('#menuBtn').addEventListener('click', () => setRail('expanded'));
    $('#main').addEventListener('click', () => {
      if (window.matchMedia('(max-width: 860px)').matches && document.body.dataset.rail === 'expanded') setRail('collapsed');
    });

    $('#cmdOpen').addEventListener('click', openPalette);
    $('#paletteScrim').addEventListener('click', closePalette);
    $('#paletteInput').addEventListener('input', (e) => renderPalette(e.target.value));

    $('#drawerScrim').addEventListener('click', () => closeDrawer(false));
    $('#drawerClose').addEventListener('click', () => closeDrawer(false));
    $('#drawerClaim').addEventListener('click', () => {
      const a = state.alarms.find((x) => x.id === state.openAlarmId);
      if (a) { a.owner = '林可'; if (a.status === 'OPEN') a.status = 'INVESTIGATING'; renderAlarms(); openDrawer(a.id); announce('已认领 ' + a.id); }
    });
    $('#drawerInvestigate').addEventListener('click', () => {
      const a = state.alarms.find((x) => x.id === state.openAlarmId);
      if (!a) return;
      if (state.noteDirty) { announce('备注尚未保存，已阻止状态变更'); $('#noteInput')?.focus(); return; }
      if (a.status === 'INVESTIGATING') { announce('该告警已是 INVESTIGATING'); return; }
      a.status = 'INVESTIGATING';
      if (!a.owner) a.owner = '林可';
      renderAlarms();
      openDrawer(a.id);
      announce('状态已更新：INVESTIGATING');
    });
    $('#drawerLogs').addEventListener('click', () => {
      const a = state.alarms.find((x) => x.id === state.openAlarmId);
      closeDrawer(true);
      go('search');
      if (a) {
        const q = 'entity="' + a.entity + '" | head 50';
        $('#queryInput').value = q;
        state.draftQuery = q;
        runSearch();
      }
    });

    $('#alarmSearch').addEventListener('input', (e) => { state.query = e.target.value; renderAlarms(); });
    $('#statusFilter').addEventListener('change', (e) => {
      state.statusFilter = e.target.value;
      state.savedView = e.target.value;
      $$('.savedviews__tab').forEach((t) => t.setAttribute('aria-selected', String(t.dataset.viewFilter === e.target.value)));
      renderAlarms();
    });
    $('#sortBtn').addEventListener('click', () => {
      state.sortDesc = !state.sortDesc;
      $('#sortBtn').innerHTML = '<svg width="14" height="14" aria-hidden="true"><use href="#i-sort"/></svg>发生时间 ' +
        (state.sortDesc ? '↓' : '↑');
      renderAlarms();
    });
    $('#moreFilters').addEventListener('click', () => {
      const box = $('#extraFilters');
      box.hidden = !box.hidden;
      $('#moreFilters').setAttribute('aria-expanded', String(!box.hidden));
    });
    $('#ownerFilter').addEventListener('change', (e) => { state.ownerFilter = e.target.value; renderAlarms(); });
    $('#riskChip').addEventListener('click', () => {
      state.minRisk = !state.minRisk;
      $('#riskChip').classList.toggle('is-on', state.minRisk);
      $('#riskChip').setAttribute('aria-pressed', String(state.minRisk));
      renderAlarms();
    });
    $('#resetExtra').addEventListener('click', () => {
      state.ownerFilter = '';
      state.minRisk = false;
      $('#ownerFilter').value = '';
      $('#riskChip').classList.remove('is-on');
      $('#riskChip').setAttribute('aria-pressed', 'false');
      renderAlarms();
    });
    $('#rangeFilter').addEventListener('change', () => announce('列表按固定过滤窗口重查，迟到事件可能改变匹配'));
    $('#checkAll').addEventListener('change', (e) => {
      const boxes = $$('#alarmBody [data-row-check]');
      boxes.forEach((cb) => { cb.checked = e.target.checked; if (cb.checked) state.selected.add(cb.dataset.rowCheck); else state.selected.delete(cb.dataset.rowCheck); });
      updateSelectionUi();
    });
    $$('.savedviews__tab').forEach((t) => t.addEventListener('click', () => setSavedView(t.dataset.viewFilter)));
    $$('[data-reset-filters]').forEach((b) => b.addEventListener('click', () => {
      state.sevFilter.clear(); state.query = ''; $('#alarmSearch').value = '';
      state.ownerFilter = ''; state.minRisk = false;
      $('#ownerFilter').value = '';
      $('#riskChip').classList.remove('is-on');
      $('#riskChip').setAttribute('aria-pressed', 'false');
      $('#statusFilter').value = 'ACTIVE'; state.statusFilter = 'ACTIVE'; state.savedView = 'ACTIVE';
      $$('.savedviews__tab').forEach((t) => t.setAttribute('aria-selected', String(t.dataset.viewFilter === 'ACTIVE')));
      renderSevChips(); renderAlarms();
    }));
    $('#claimTop').addEventListener('click', claimSelected);
    $('#exportBtn').addEventListener('click', () => announce('导出任务已提交，范围与筛选条件一致'));

    $('#queryInput').addEventListener('input', syncDraft);
    $('#queryInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); runSearch(); }
      if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey && !e.isComposing) { e.preventDefault(); runSearch(); }
    });
    $('#runQuery').addEventListener('click', runSearch);
    $('#revertQuery').addEventListener('click', () => { $('#queryInput').value = state.appliedQuery; syncDraft(); });
    $('#queryClear').addEventListener('click', () => { $('#queryInput').value = ''; syncDraft(); $('#queryInput').focus(); });
    $('#emptyReset').addEventListener('click', () => { $('#timeRange').value = '近 7 天'; runSearch(); });
    $('#jumpAlarm').addEventListener('click', () => go('alarms'));
    $('#viewToggle').addEventListener('click', () => {
      state.viewMode = state.viewMode === 'list' ? 'table' : 'list';
      const btn = $('#viewToggle');
      btn.textContent = state.viewMode === 'list' ? '表格视图' : '列表视图';
      btn.setAttribute('aria-pressed', String(state.viewMode === 'table'));
      renderEvents();
    });
    $('#saveQuery').addEventListener('click', () => {
      const key = 'socp.saved.t-default';
      try {
        const list = JSON.parse(localStorage.getItem(key) || '[]');
        if (list.some((x) => x.q === state.appliedQuery)) { announce('该查询已保存'); return; }
        list.unshift({ q: state.appliedQuery, at: Date.now() });
        localStorage.setItem(key, JSON.stringify(list.slice(0, 20)));
        announce('已保存查询，共 ' + list.length + ' 条');
      } catch (e) { announce('本地存储不可用，未保存'); }
    });
    $('#fieldsToggle').addEventListener('click', () => $('.searchlayout').classList.toggle('show-fields'));
    $('#fieldSearch').addEventListener('input', (e) => {
      const q = e.target.value.toLowerCase();
      $$('#fieldList li').forEach((li) => { li.style.display = li.dataset.field.toLowerCase().includes(q) ? '' : 'none'; });
    });

    $('#refreshBtn').addEventListener('click', refresh);
    $('#ovRefresh').addEventListener('click', refresh);
    $$('.seg__btn').forEach((b) => b.addEventListener('click', () => {
      $$('.seg__btn').forEach((x) => { x.classList.remove('is-on'); x.setAttribute('aria-pressed', 'false'); });
      b.classList.add('is-on'); b.setAttribute('aria-pressed', 'true');
      renderCharts();
      announce('时间范围：' + b.textContent);
    }));
    $$('[data-goto]').forEach((a) => a.addEventListener('click', (e) => {
      e.preventDefault(); go(a.dataset.goto);
    }));

    // global keys
    document.addEventListener('keydown', (e) => {
      const inField = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);

      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        $('#palette').hidden ? openPalette() : closePalette();
        return;
      }
      if (e.key === '\\' && !inField) {
        e.preventDefault();
        setRail(document.body.dataset.rail === 'expanded' ? 'collapsed' : 'expanded');
        return;
      }
      if (e.key === 'Escape') {
        if (!$('#palette').hidden) { closePalette(); return; }
        if (!$('#drawer').hidden) { closeDrawer(false); return; }
        if (window.matchMedia('(max-width: 860px)').matches) setRail('collapsed');
        return;
      }
      if (!$('#palette').hidden) {
        if (e.key === 'ArrowDown') { e.preventDefault(); setPaletteIndex(state.paletteIndex + 1); }
        if (e.key === 'ArrowUp') { e.preventDefault(); setPaletteIndex(state.paletteIndex - 1); }
        if (e.key === 'Home') { e.preventDefault(); setPaletteIndex(0); }
        if (e.key === 'End') { e.preventDefault(); setPaletteIndex(state.paletteItems.length - 1); }
        if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); activatePalette(state.paletteIndex); }
        return;
      }
      if (!$('#drawer').hidden && e.key === 'Tab') { trapFocus(e, $('#drawer')); return; }
      if (e.key === '/' && !inField) { e.preventDefault(); go('search'); setTimeout(() => $('#queryInput').focus(), 60); }
    });

    // freshness ticker
    setInterval(() => {
      state.freshSeconds += 5;
      const label = state.freshSeconds < 60 ? state.freshSeconds + 's 前' : Math.floor(state.freshSeconds / 60) + 'm 前';
      $('#freshTime').textContent = label;
      $('#freshness').classList.toggle('is-stale', state.freshSeconds > 120);
    }, 5000);

    let rt;
    window.addEventListener('resize', () => {
      clearTimeout(rt);
      rt = setTimeout(() => { if (state.view === 'overview') renderCharts(); }, 160);
    });

    if (window.matchMedia('(max-width: 860px)').matches) setRail('collapsed');
    else {
      const saved = (() => { try { return localStorage.getItem('socp.rail'); } catch (e) { return null; } })();
      setRail(saved === 'collapsed' ? 'collapsed' : 'expanded');
    }

    // 原型自检钩子：仅用于演示与截图核对
    window.__socp = { go, openDrawer, openPalette, toggleTheme, followSystemTheme };

    // Cmd/Ctrl hint differs on Windows
    if (!/Mac|iPhone|iPad/.test(navigator.platform)) {
      $$('[data-kbd]').forEach((k) => { k.textContent = k.textContent.replace('⌘', 'Ctrl+'); });
    }
  }

  function refresh() {
    const box = $('#freshness');
    box.classList.add('is-loading');
    $('#refreshBtn').disabled = true;
    setTimeout(() => {
      box.classList.remove('is-loading', 'is-stale');
      state.freshSeconds = 0;
      $('#freshTime').textContent = '刚刚';
      $('#refreshBtn').disabled = false;
      renderCharts();
      announce('四类数据源已刷新（各自独立时间戳）');
    }, reduceMotion() ? 120 : 700);
  }

  document.addEventListener('DOMContentLoaded', init);
})();
