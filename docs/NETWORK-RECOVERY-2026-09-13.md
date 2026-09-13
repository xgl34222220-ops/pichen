# 辟尘第二轮：网络恢复与规则更新并发

基线：PR #1 的 `95880c3f0a698ba3217c3f1d45e42246989edcaa`，在第一轮 CNAME 与规则档位代码上继续修改。未修改模块代码、版本号或原签名下载包。

## 本轮代码变更

### 网络切换不沿用旧会话

- 使用 Android 为辟尘自身选择的默认网络；App 本身已排除在自己的 DNS VPN 外。只接收 INTERNET、NOT_VPN 且非 VPN transport 的网络，不猜测 Wi-Fi 必然优于移动网络，不主动唤醒备用网络。
- 等待有序的网络能力和链路属性回调，再使用 Network.bindSocket 绑定 UDP / TCP 上游；DoH 使用该 Network.openConnection，保留系统 TLS 与主机名校验。
- Wi-Fi/流量切换、链路属性变化、系统阻断联网及网络恢复都会更新网络代次。旧 DNS 缓存不跨代次复用，旧请求不能写回新网络；旧 DoH 连接与退避状态释放。通过 setUnderlyingNetworks 向系统报告实际上游。
- 断网/等候回调期间，VPN 接口和本地规则仍保留；本地命中的域名照常拦截，其他请求直接返回 SERVFAIL，不占上游工作队列，也不偷偷改用明文 DNS。
- 默认网络事件不发 Root 命令、不重建 TUN、不反复暂停/恢复 hosts。停止服务会注销回调；应用收到新默认网络后自动尝试恢复查询。
- 日志区分“等待网络”“网络切换”“请求繁忙”；首页、DNS 设置及诊断摘要显示上游状态。网络已连接不代表 DNS 服务或互联网必定可用。

### 规则下载不长时间持有配置锁

- 自动/手动更新共用一个即时拒绝重复任务的下载闸门；不堆积重复下载。
- 下载和解析在配置锁外执行，后台更新不再因为网络慢而长期挡住前台白名单编辑、回滚、保护准备中的本地加载。
- 最终提交前在配置锁内重新检查本地与模块快照，下载期间发生名单、档位、回滚或模块配置变化，丢弃过期批次；保留刚修改的当前状态，不自动覆盖。
- 本地模式下，来源内容完全不变且快照并非模块导入时，仅记录检查时间，不重写有效规则，不清除可用的上一份回滚快照。模块模式仍执行其原有整批导入；不声称模块已实现相同的免写更新。
- 保留 HTTPS、单源 8 MiB、整批 32 MiB、单源 90 秒/整批 180 秒的原有有界下载与中断机制。
- 外部模块客户端不与 App 的 Java 锁共享事务；重新同步能发现已经完成的模块变更，但不是跨进程 compare-and-swap。模块自己的导入锁和事务继续负责最终一致性。

### 请求记录清空并发

请求记录追加、清空与开关修改共用进程内锁，防止“先读取旧数组、用户清空、旧数组又被追加写回”的竞态。清空之后新完成的请求仍可正常记录；历史统计不重置。不采集 Wi-Fi 名称或上传日志。

## 已执行的本地验证

- Java 协议、缓存、DoH、CNAME、档位及新网络状态/更新闸门：252 条断言通过。
- 其中新增 NetworkEpoch 27 条、RuleUpdateGate 17 条；包括旧网络回调、同网 DNS 变化、离线重连、网络阻断恢复、旧缓存隔离、迟到响应、并发编辑和取消。
- 既有固定种子畸形输入生成用例共 150,000 个通过。
- 既有宿主隔离模块回归 18/18 通过。
- 本地 Java 测试验证的是纯状态模型和协议代码，不是 Android framework 的真实回调、系统路由或实际 Wi-Fi/蜂窝切换。完整 Android 编译以本轮 CI 的结果为准。

## 尚未完成的真机验收

K80 至尊版与一加 15：UDP/DoH 各开启保护后 Wi-Fi→流量→Wi-Fi，切换飞行模式，熄屏后恢复；检查域名解析、例外应用、CNAME、Root 暂停状态和重启。登录支付、私人 DNS、其他代理与 OEM 网络策略也必须分别测试。这里没有真实设备连接，未将模型测试写成真机通过。

## 发布边界

本轮是未发布的源码改进。`downloads/` 仍是原 `0.3.0-beta.1`，其中不包含这两轮的新功能。未取得原签名私钥；CI 临时签名 APK 不作为可覆盖安装包交付。没有换签冒充升级，没有增加第二个包名，模块 ZIP 仍与基线一致。正式交付必须统一版本与原签名并重新校验。

## 核对的官方依据（2026-09-13）

- Android 默认网络选择、回调线程与同步查询竞态： https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- NetworkCallback 有序回调与注销： https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- 绑定上游 socket、在指定 Network 打开 HTTPS： https://developer.android.com/reference/android/net/Network
- 向系统报告实际上游网络： https://developer.android.com/reference/android/net/VpnService#setUnderlyingNetworks(android.net.Network[])
