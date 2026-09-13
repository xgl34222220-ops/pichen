# 辟尘：去广告方案对照与实现取舍

读取日期：2026-09-12（UTC）。本次实际打开了下列项目的官方仓库或官方文档，覆盖 9 个有代表性的去广告应用、模块及规则项目，并补充 Android 与 root 管理器文档。它们覆盖主要技术路线，不代表穷尽市面所有产品。本文记录研究结论和建议，**不是本版已实现功能清单，也不代表任何手机已经实测通过**。

## 1. 对照结果

| 方案 | 官方资料确认的能力 | 辟尘可借鉴的设计 | 实现边界 | 许可证记录 |
| --- | --- | --- | --- | --- |
| [bindhosts](https://github.com/bindhosts/bindhosts) | 面向 Magisk、KernelSU、APatch；支持管理器挂载、bind mount、OverlayFS 等方式；有 WebUI、动作按钮与自更新 | 挂载能力探测；简单暂停/恢复；模块独立运行；按实际挂载结果报告状态 | 多种挂载方式需要分别验证，不能仅检查模块目录存在；不要同时抢写别的 hosts 模块 | 官方仓库标注 WTFPL；本次只借鉴架构，不复制代码 |
| [AdAway](https://github.com/AdAway/AdAway) | 同时提供 root hosts 与本地 VPN 路线；可添加 hosts 来源；官方权限说明把应用排除用于 VPN | 来源管理、用户例外、配置导入导出；把 root 与 VPN 模式明确分开 | root hosts 不因此获得按应用隔离能力；VPN 模式需要系统 VPN 授权 | App：GPLv3+；规则源另按各自许可处理，不能用 App 许可概括全部规则 |
| [AdGuard for Android](https://adguard.com/kb/adguard-for-android/features/app-management/) | 按应用决定是否进入过滤路径、是否过滤内容、HTTPS 与代理；有应用统计 | 一个应用一个详情页；路由与过滤分开；错误可由近期活动追踪 | 域名拦截不等于 HTTPS 内容过滤；不能把证书安装、系统应用代理当默认操作 | Android 成品遵循 [AdGuard EULA](https://adguard.com/en/eula.html)；[公开仓库](https://github.com/AdguardTeam/AdguardForAndroid)是问题跟踪器，不是可直接内置的完整开源 App |
| [RethinkDNS](https://github.com/celzero/rethink-app) | DNS、Firewall、VPN 三种工作方向；加密 DNS、按应用网络控制、连接观察与分流 | 日志关联应用、域名、规则；按应用例外；让用户看清为何拦截 | 是完整网络引擎；不可通过 hosts 条目数伪造其连接日志或应用统计 | 官方仓库标注 Apache-2.0；若采用其后端库，还须逐个核对依赖许可 |
| [Re-Malwack](https://github.com/ZG089/Re-Malwack) | 多档 hosts 配置、暂停/恢复、域名查询、白黑名单、来源条目数、导入 AdAway/bindhosts 配置、多 root 兼容 | 从域名查询直接修复误杀；按来源展示贡献；无需卸载即可暂停 | 官方明确 hosts 规则的边界；不照搬“所有广告”营销承诺，也不照搬给普通浏览器授予 root 的建议 | 官方仓库标注 GPL-3.0；整合源保留各自出处 |
| [Energized Protection](https://github.com/EnergizedProtection/block) | 将多个来源去重并做成不同保护档位，输出多种格式 | 轻量/均衡/加强档位；规则去重；对资源有限的设备控制规则规模 | 旧项目的域名与下载端点须重新验证，不能仅因知名就默认启用；规则数量不等于效果 | README 区分内容 MIT 与用于格式化/展示内容的底层源码 CC BY-NC-SA 4.0；不能笼统当作全仓 MIT |
| [StevenBlack hosts](https://github.com/StevenBlack/hosts) | 聚合多份 hosts、去重、分扩展类别；公开各上游的许可与问题入口 | 来源追踪、规则去重、基础广告与额外类别分开 | 不默认开启社交、成人、赌博等与去广告不同的内容类别；精确 hosts 域名不自动包含子域 | 自有仓库代码/数据有 MIT 标识；聚合内容包含 CC BY 等不同许可，按其来源清单保留归属 |
| [AdGuard DNS Filter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | 从多个列表汇合、简化成 DNS 过滤规则；官方明确要求基础 Adblock 语法支持 | 未来 DNS 引擎保留例外、域名后缀及规则语义；构建时验证格式 | **不可简单剥除 `||`、`^`、`@@` 后塞进 hosts 并声称等效**；例外、子域、修饰符会丢失 | 官方仓库标注 GPL-3.0；上游规则仍需保留相关说明 |
| [HaGeZi DNS Blocklists](https://github.com/hagezi/dns-blocklists) | 按力度分级，提供面向移动设备的精简列表，并说明误杀风险与格式选择 | 默认规模适中；加强档位展示取舍；提供误杀修复入口 | 同系列通常选择一个档位，避免叠加；当前主仓把旧 hosts 格式迁至独立仓，端点应从当前官方文档确认 | 官方仓库标注 GPL-3.0；0.3.0-beta.1 另行内置 Light 精确域名快照，见模块第三方声明 |

以上记录仅用于实现来源追溯。若未来复制代码、内置列表或二次分发二进制，必须对实际使用的文件和版本保留许可及归属，不能只引用这张表。

## 2. “按应用放行”必须有可验证的含义

### 2.1 全局 hosts 的局限

全局 hosts 只有域名与地址映射，没有包名、UID 或应用策略。将 `com.example.app` 写入一个配置文件，本身不会令该应用绕过全局 hosts。对一个应用所用域名做白名单，会同时影响其他使用这些域名的应用；这种功能应叫“相关域名放行”，不能标成“仅放行这个应用”。AdAway 官方将应用排除明确用于 VPN，印证了这两条路线应分开实现。[AdAway 官方说明](https://github.com/AdAway/AdAway)

### 2.2 进程挂载命名空间只能作为实验方案

可以研究为特定应用进程提供原始 hosts 视图，但不能仅以 `nsenter` 后看到原始文件作为验收。AOSP bionic 的 `getaddrinfo` 路径对普通域名先尝试 `android_getaddrinfo_proxy`；本地 hosts 读取是另一路径。由此推断，应用自己的 hosts 文件视图并不保证决定所有实际解析结果。必须按系统版本核查实际解析器行为，并在目标应用内验证。[AOSP bionic getaddrinfo 源码](https://android.googlesource.com/platform/bionic/+/master/libc/dns/net/getaddrinfo.c)

实验时至少检查：主进程、冒号子进程、独立进程、WebView、共享 UID、工作资料/应用分身；应用冷启动和进程重建；root 管理器是否重置挂载；缓存解析是否尚未过期。轮询正在运行的进程再修改挂载，还存在应用已经发出首个请求的时间窗口。

KernelSU 的“卸载模块”是对应用卸载模块挂载的通用控制，可能连洛书字体等其他模块一起影响，不能为了去广告放行而静默替用户打开该项。[KernelSU App Profile](https://kernelsu.org/guide/app-profile.html)

### 2.3 应用策略路线对照

| 路线 | 优点 | 实际需要解决的问题 | 本阶段判断 |
| --- | --- | --- | --- |
| Android `VpnService` + 本地 DNS/网络引擎 | 平台提供按应用的 allowed/disallowed list | VPN 授权、与用户现有 VPN 的互斥、前台服务、网络切换、IPv6、配置重建；不能让其他全局 hosts 继续拦截已排除应用 | 本次选定 DNS-only VPN 方向，与全局 hosts 模式互斥，完成情况须看测试报告 |
| root DNS 转发 + 经验证的 UID 策略 | 可以不占系统 VPN 位 | Android DNS 可能由共享解析器代发，不能假定 UDP 53 socket UID 总是应用 UID；还要处理缓存隔离、共享 UID、IPv6、DoH/DoT、代理和断网恢复 | 需要单独工程阶段和真机验证，不能仅添加 `iptables --uid-owner` 就宣布完成 |
| 应用前台期间暂停全局 hosts | 不修改应用本身，能帮助临时需要广告奖励或恢复网络的使用场景 | 检测延迟、分屏/画中画、退出后恢复；暂停期间其他应用也不受此模块的 hosts 拦截；应用缓存未必立即清除 | 本次不采用，不能代替用户要的应用级放行 |
| 选应用后放行其相关域名 | 规则实现较简单 | 无法可靠静态推断每个应用全部域名；共享广告域名对其他应用也会放行 | 可以做带解释的域名方案，不能冒充严格应用隔离 |

Android 官方要求每条 VPN 连接选择 allowed list 或 disallowed list 之一；修改列表需要重建连接。启用“阻止不通过 VPN 的连接”时，被排除应用可能无法联网。[Android VPN 开发指南](https://developer.android.com/develop/connectivity/vpn)

### 2.4 本次选定：DNS-only VPN 应用模式

本次工程决策是以真正的 `VpnService` 实现应用排除；全局 hosts 模式与应用模式只能启用一个。切换到应用模式前，需要确认本模块全局 hosts 已恢复，否则被 VPN 排除的应用仍可能遭到 hosts 拦截。DNS-only 表示只接管配置到虚拟 DNS 地址的解析流量，并不意味着接管应用全部网络请求；自带 DoH/DoT、硬编码 DNS、直连 IP 等不保证进入此路径。

平台接口约束：

- `VpnService.Builder.addDisallowedApplication(packageName)` 从 API 21 起可用；被排除应用按 VPN 未运行时联网。包名必须对应已安装应用；不能混用 allowed 与 disallowed 集合。[官方 Builder API](https://developer.android.com/reference/android/net/VpnService.Builder)
- 启动前调用 `VpnService.prepare()`，由系统完成 VPN 授权；处理 `onRevoke()`，其他 VPN 接管或用户关闭后释放接口、线程和 socket。[官方 VpnService API](https://developer.android.com/reference/android/net/VpnService)
- 服务声明 `android.permission.BIND_VPN_SERVICE` 和 `android.net.VpnService` action；Android 8+ 启动后及时进入前台。上游 UDP/TCP socket 调用 `protect()` 并检查返回值，避免流量被再次送回 TUN。[官方 VpnService API](https://developer.android.com/reference/android/net/VpnService)
- 面向 Android 14+ 时使用适用的前台服务类型。系统已经配置的 VPN App 属于 `systemExempted` 适用范围，需要 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SYSTEM_EXEMPTED` 与对应服务类型；必须满足其配置条件。[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)

验收应包含：应用 A 受控域名被阻断，排除应用 B 同一域名正常解析；更改排除项后重建 VPN；停止/被撤销后正常恢复联网；Wi-Fi/蜂窝切换后 DNS 可用；上游超时有明确失败结果；查询日志仅统计实际进入引擎的请求，不将其当作整机全部广告请求。

## 3. 模块安装与挂载兼容

### 安装器

- 把模块 ID、版本、`customize.sh`、生命周期脚本放在 ZIP 根目录，检查压缩包完整性，避免再多套一层文件夹。
- 使用 root 管理器的正常模块安装协议；不得以不存在 `magisk` 命令为由直接拒绝所有 KernelSU 系设备。
- 安装脚本按真实环境探测 Magisk/KernelSU/APatch 及其能力。KernelSU 官方安装环境提供 `KSU=true`、`MODPATH`、`ZIPFILE` 等变量，`BOOTMODE` 为 true；`customize.sh` 由安装器 source，不应末尾擅自 `exit`。
- 首版可明确只支持 Android 内的管理器安装；KernelSU 官方不支持通过自定义 Recovery 安装其模块。不要为一个未测试的 Recovery 分支增加虚假的兼容声明。

来源：[Magisk Developer Guides](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU Module guide](https://kernelsu.org/guide/module.html)。

### App 内置安装器的真实 CLI

| 管理器 | 安装入口 | 路径探测与官方证据 |
| --- | --- | --- |
| Magisk | `magisk --install-module ZIP` | 从获授权 root shell 的 `command -v magisk` 解析，再用 `-v`/`-V` 确认；不假定唯一 `/sbin` 路径。[官方工具指南](https://topjohnwu.github.io/Magisk/tools.html) |
| KernelSU | `ksud module install ZIP` | 官方常量本体为 `/data/adb/ksud`，另有 `/data/adb/ksu/bin/ksud` 链接。先 `test -x`，再探测帮助/版本。[官方 defs.rs](https://raw.githubusercontent.com/tiann/KernelSU/main/userspace/ksud/src/defs.rs)、[官方 cli.rs](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/cli.rs) |
| APatch | `apd module install ZIP` | 官方常量本体为 `/data/adb/apd`；`/data/adb/ap/bin/` 是工具目录，不能认定 apd 一定在那里。[官方 defs.rs](https://raw.githubusercontent.com/bmax121/APatch/main/apd/src/defs.rs)、[官方 cli.rs](https://raw.githubusercontent.com/bmax121/APatch/main/apd/src/cli.rs) |

KernelSU 的 module 命令会进入 PID 1 的挂载命名空间，因此不能假定 App 私有 cache 路径在安装器执行时仍可见。建议把 APK 内置 ZIP 经获授权 root 进程写入 `/data/adb/` 下辟尘专属随机临时目录，目录权限 0700、文件权限 0600，验证摘要后调用已探测的 CLI，保留退出码和日志并最终清理。本方案是辟尘的工程取舍，不是上述框架规定的统一暂存目录。只使用正常 root 授权；不读取 APatch SuperKey，不修改授权数据库。

### 挂载

安装成功、配置生成、真实挂载、应用解析是四个不同状态。当前 KernelSU 官方文档指出，普通依赖挂载的模块在未安装 metamodule 时不会自动挂载；具备自己挂载逻辑的模块应独立验证，不要仅依赖 `system/etc/hosts` 路径存在。[KernelSU Metamodule](https://kernelsu.org/guide/metamodule.html)

建议辟尘：探测实际活动路径与内容摘要；显示挂载方式及错误；检测其它 hosts 管理器冲突；暂停、更新、回滚后再次读回验证。保留一份已验证原始 hosts 和一份最近有效规则，不把损坏下载覆盖到正在使用的文件。开机阻塞阶段不做网络下载和巨量规则合并，避免拖慢系统启动。[Magisk 启动阶段说明](https://topjohnwu.github.io/Magisk/guides.html)

### ReSukiSU / 用户所说的 ResuKSU

本次确认存在官方 [ReSukiSU 仓库](https://github.com/ReSukiSU/ReSukiSU)，其文档说明使用 metamodule 模块体系并支持多个管理器。用户设备显示的“ResuKSU”是否就是此分支，应由版本/日志确认，不凭相近名称认定。兼容判断必须基于可用安装环境、`ksud` 能力和实际挂载结果，不能只做管理器包名白名单。该仓库将内核标为 GPL-2.0-only，其余多数部分为 GPL-3.0-or-later，图标另有约定；辟尘无需复制它的图标或内核。

## 4. 首个可交付版建议范围

下表是工程验收目标，最终以本版源码、构建记录和测试结果确认完成情况。

| 功能 | 推荐实现与验收 |
| --- | --- |
| App 内置模块 ZIP | APK assets 内置同版本 ZIP 与摘要；App 可导出该包；如实现 root 安装，只调用确认存在的管理器接口，显示完整退出码及日志；安装后读回 module.prop。APK 内置模块不等于把 APK 再塞入模块造成循环膨胀 |
| 独立模块包 | 无 App 也能保持已应用规则；可从管理器暂停/恢复；安装过程不强制在线下载 App |
| 应用放行 | 使用本地 DNS-only VPN 的真实排除名单；与 hosts 模式互斥；明确占用 VPN 位置及仅过滤进入该 DNS 引擎的请求；不使用前台全局暂停冒充隔离 |
| root 兼容修复 | Magisk、KernelSU/ReSukiSU、APatch 安装入口分开验证；无管理器命令或不支持挂载时明确报错，不能返回绿色成功 |
| 来源管理 | 每个来源有名称、URL、格式、启用状态、最后成功更新时间、有效域名数；只启用解析器真正支持的格式 |
| 更新与回滚 | 下载到临时文件；校验大小、格式和有效内容；去重合并；保留最近有效版本；失败保留当前规则；错误信息指出失败源 |
| 域名白黑名单 | 明确“精确域名”和“该域及子域”区别；白名单优先级清楚；拒绝 URL 路径、命令片段、非法域名；修改后读回并支持删除 |
| 查询与诊断 | 输入域名后显示是否在规则中、命中来源/手动规则、是否被例外覆盖；分清“规则匹配”与“实际网络解析测试” |
| 暂停/恢复 | 单击暂停，一次操作可恢复；状态与活动 hosts 一致；重启后按已保存意图恢复；不能因 App 退出就丢失设置 |
| 有边界的日志 | 记录安装、更新、挂载、暂停与错误；本地保留并限制大小；导出前给出内容。没有 DNS 捕获引擎时不显示虚构的“今日拦截次数” |
| 配置导入导出 | 导出来源、白黑名单、档位和版本；导入前验证结构；不导出 root 凭据等无关内容 |
| UI | 首页只突出开关、当前状态、规则更新时间和问题入口；来源/规则/应用模式各自有明确状态；应用放行显示它实际采用哪种语义 |

这些建议吸收了上面对照项目的来源管理、透明状态、误杀修复和模式分离思想。回滚、原子替换、状态读回、包摘要等是针对辟尘交付失败风险提出的工程要求，不应反向宣传为所有参考项目都已具备的特性。

## 5. DNS 引擎的进一步完善

具备真正 DNS 请求观察能力后，再提供“应用 → 请求域名 → 命中规则 → 一次放行/永久放行”的完整闭环。每条日志至少有时间、查询名、类型、结果、命中规则、可确认时的应用身份；身份不明时显示未知，不能按最近前台应用猜测。日志轮转、保存时长与清除入口同时交付。RethinkDNS 的应用关联与网络观察是此阶段的设计参考。[RethinkDNS 官方仓库](https://github.com/celzero/rethink-app)

加密 DNS 上游的超时、失败策略、IPv4/IPv6 回答、缓存与网络切换应明确配置；不能为了“不断网”静默降级到用户未选择的普通 DNS。AdGuard 将上游、回退、超时、阻断响应及加密 DNS 过滤分开配置，可借鉴其分层设计，而非首版塞入大量不会生效的开关。[AdGuard 低层设置指南](https://adguard.com/kb/adguard-for-android/features/low-level-settings/)

## 6. 不可承诺的效果与验收底线

- 不承诺屏蔽所有广告：与正常内容同域的广告、应用内写死内容、直连 IP、自带加密 DNS、部分代理流量可能超出 hosts 的作用范围。不能把网络过滤等同于去除页面占位或删除应用内控件。
- 不承诺某种规则数量越多越好；默认档位须以误杀率、加载速度、存储占用和实际应用体验选择。
- 不承诺“支持全部 root”或“Android 16 已完美兼容”，除非相应安装、应用解析与重启行为已实测。
- 不把测试环境内脚本成功当作 K80 至尊版或一加 15 真机通过。至少单独记录安装成功、hosts 生效、浏览器实际解析、暂停恢复、断网更新保留旧规则、连续重启六类结果。
- 不把 APK 有文件当作已交付；交付必须提供可下载 APK、模块 ZIP、可复现源码及摘要，并说明签名是否能覆盖安装已有 APK。

本对照研究没有复制上述项目源码或产品素材。实际分发的第三方规则及许可另见 `module/THIRD_PARTY_NOTICES.md`；保护模式与联网设置由用户在 App 中选择。


## 7. 2026-09-13 补充对照及本轮落实

本轮重新读取 bindhosts、AdAway、Re-Malwack、RethinkDNS 官方仓库，并扩展到下列方案；
研究范围是代表性技术路线，不宣称穷尽全部商业产品、模块、闭源变体或当前所有版本。
原有表格中的历史记录与本轮代码落实应分别阅读，未实际移植的功能不能列入产品功能清单。

| 补充方案 | 官方出处与本次确认 | 本轮取舍 |
| --- | --- | --- |
| personalDNSfilter | [官方配置](https://github.com/IngoZenz/personaldnsfilter/blob/master/app/src/main/assets/dnsfilter.conf) 明确包含 CNAME cloaking 检查 | 自行实现有边界的查询关联 CNAME 链检查，不复制引擎代码；默认关闭以便逐步验证 |
| AdGuard Home / DNS | [官方说明](https://adguard-dns.io/en/welcome.html) 说明请求和响应都可参与 CNAME 防绕过 | 请求名与响应目标分别检查，保留显式白名单；不宣传为完整 AdGuard 内容过滤 |
| 10007_auto | [官方仓库](https://github.com/lingeringsound/10007_auto) 提供自动更新广告 hosts | 借鉴模块独立维护思路；暂未内置其规则，不能写成已移植所有规则 |
| GKD | [官方仓库](https://github.com/gkd-kit/gkd) 使用无障碍、高级选择器和订阅规则做自定义屏幕点击 | 作为“控件/开屏处理”的独立路线评估，本轮没有加入无障碍自动点击，也不把 DNS 拦截说成已跳过开屏 |
| anti-AD | [官方仓库](https://github.com/privacy-protection-tools/anti-AD) 发布多格式域名规则，并区分可能争议或影响业务的域名 | 规则格式与误拦须单独评估，本轮未盲目叠加到默认列表 |
| AdRules | [官方仓库](https://github.com/Cats-Team/AdRules) 面向多种过滤器提供不同规则格式 | 不将 Adblock URL/例外语义剥掉后转为 hosts；本轮不把其全部规则加入 |
| 秋风 AWAvenue | [官方仓库](https://github.com/TG-Twilight/AWAvenue-Ads-Rule) 提供广告过滤规则与多平台使用方式 | 保留已内置的纯广告来源；隐私跟踪源仍独立选择 |
| Blokada | [官方仓库](https://github.com/blokadaorg/blokada) 覆盖本地及云端产品路线 | 借鉴保护状态与控制入口；本轮不增加云端账号、日志收集或付费代理依赖 |

直接落实的是 Re-Malwack 式档位选择的交互思想、personalDNSfilter/AdGuard 的响应别名检查方向，
以及查询活动到误拦恢复的操作链路。所有新代码为本轮独立实现；没有直接复制参考项目源码。
档位复用辟尘已有四个来源，并非这些项目规则的并集；新增来源仍须逐份核对许可证、格式和误拦。
