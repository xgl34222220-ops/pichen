# 辟尘 Bichen 0.3.0-beta.1

Android 去广告管理 App，内置同版本 hosts 模块，支持本地 DNS VPN 与按应用放行。

[下载 App](https://github.com/xgl34222220-ops/pichen/raw/refs/heads/main/downloads/Bichen-0.3.0-beta.1.apk) · [下载模块 ZIP](https://github.com/xgl34222220-ops/pichen/raw/refs/heads/main/downloads/Bichen-0.3.0-beta.1-module.zip) · [文件校验](downloads/SHA256SUMS.txt)

## 当前开发分支的未发布改进

本分支增加可选 CNAME 别名链检查、轻量/均衡/加强档位和可追溯的别名拦截活动，详见 [实现与验收](docs/IMPROVEMENTS-2026-09-13.md)。**上面的已签名下载仍是原 0.3.0-beta.1，不包含这些新代码**；本分支没有冒充覆盖升级包或正式 Release。原模块内容不变。

## 未发布的第二轮改进

在 PR #1 上继续补充网络切换会话隔离、断网恢复、规则下载并发与请求记录清空竞态修复，见 [本轮说明](docs/NETWORK-RECOVERY-2026-09-13.md)。**下面的旧版下载包不包含这些源码改进。**

## 这一版改进

- 保护、应用、规则、活动四页重新设计，支持浅色与深色；应用列表搜索，改动与当前生效名单分开显示。
- 最近 DNS 活动可搜索，点开域名就能处理误拦；请求记录默认关闭，仅保存在本机。
- 可选 Cloudflare / Google 加密 DNS（DoH），失败只尝试加密备用上游，不静默切回普通 DNS。
- 新增有容量与有效期限制的 DNS 缓存；换规则、切换保护会话时隔离失效，避免旧结果继续挡住刚放行的域名。
- 新增可选 HaGeZi Light：35,280 条种子域名，默认关闭。默认有效规则 7,081 条，开启 Light 后 40,664 条；这里采用精确域名语义，不等价于后缀过滤器的全部子域覆盖。
- 完整配置整批导入、旧模块配置迁移和回滚；更新失败保留旧规则，模块改动后的同步失败会明确显示。

## 安装与升级

1. 安装上面的 **App APK**。它沿用此前交付的 `0.2.0-alpha.2` 签名，可覆盖该版；最初的 `0.1.0-alpha.3` 签名不同，需要先卸载旧 App。卸载 App 会清除 App 内配置，请先导出名单；模块的 `/data/adb/bichen` 数据无需删除。
2. 在保护页的模块管理中安装内置模块，按当前 Root 管理器提示授权。安装完成后重启，再检查实际挂载状态。
3. 也可将单独的 **`-module.zip`** 交给 Magisk / KernelSU / ReSukiSU / APatch 安装。APK、源码 ZIP 和 GitHub 的“Download ZIP”都不能当模块刷入。
4. 模块 ZIP 与 APK 内置 ZIP 的字节完全一致，构建会检查 ZIP 根目录的 `module.prop`、入口脚本、版本和 SHA-256。

这是 beta 测试版。模拟器、协议测试和宿主隔离测试的具体结果见 [验证记录](docs/VALIDATION.md)；不把它们当作 HyperOS / Android 16 或实际 Root 管理器安装通过。

## 选择保护方式

| 方式 | 适用情况 | 能力与边界 |
| --- | --- | --- |
| 模块模式 | 正在使用其他 VPN，或希望无常驻 App 进程 | 全局 hosts 精确域名过滤，不占 VPN；域名白名单对所有应用生效 |
| 应用保护 | 希望指定应用完全不经过辟尘 DNS 过滤 | 本地 DNS VPN 使用 Android 应用排除；开启前暂停辟尘 hosts，停止后恢复原状态；占用系统 VPN 位置 |

应用页修改放行名单后应用更改，系统会重建 VPN。DNS-only VPN 只接收导向本地 DNS 的 UDP 查询，不接管普通网络流量；DNS 上游遇截断可通过 TCP 重试。DoH 是本引擎向上游加密查询，不能捕获其他 App 自带的 DoH；供应商域名首次解析可能使用系统 DNS。私人 DNS、直接 IP 和代理的远端解析也可能绕过过滤。

同域广告、页面空白、内置素材、开屏计时和激励奖励无法仅靠域名过滤保证解决。模块模式不会伪造 DNS 请求数；应用模式统计实际经过引擎的请求，也不会猜测请求属于哪个应用。

## 规则与维护

内置 AdAway、秋风纯广告，另有默认关闭的秋风隐私增强与 HaGeZi Light。上游规则本次重新核对，前三份快照与上版相同。来源与许可证见 [第三方声明](module/THIRD_PARTY_NOTICES.md)。白名单优先于黑名单和订阅；先用域名排查或活动记录确认，再放行具体域名。

订阅经 HTTPS 有界下载，全量校验完成才提交；单源限制 8 MiB，合并限制 32 MiB。每日更新需手动开启，受系统网络、省电与任务调度约束，不保证固定钟点执行。列表导入、回滚与模块健康检查均保留错误说明。

## 构建与仓库产物

需要 Python 3、JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0，不依赖 Gradle 或第三方 App 运行库。

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
python3 tools/package.py --build-app
python3 tools/test_java.py
python3 -m unittest discover -s tests -p '*test.py' -v
python3 tools/verify_downloads.py
```

构建产物在 `out/`。`downloads/` 保存本次沿用原开发签名的可安装文件；GitHub Actions 重编译源码、运行回归、核验这里的签名和包内容。CI 不分发随机签名 APK，避免无法覆盖升级。自动构建不代表已经发布正式 Release。

持续覆盖升级必须保管同一签名私钥。自建时设置 `BICHEN_KEYSTORE`、`BICHEN_KEY_ALIAS`、`BICHEN_STOREPASS`、`BICHEN_KEYPASS`；CI 可配置同名签名参数与 `BICHEN_KEYSTORE_BASE64` Secret。私钥不进入仓库和源码包。

## 参考与许可

对照了 bindhosts、AdAway、AdGuard、RethinkDNS 等 9 个代表项目，见 [设计对照](docs/REFERENCE_COMPARISON.md)，不声称穷尽市面所有产品。模块从用户提供的 `0.1.0-alpha.3` 继续开发；管理 App 以 Java 重新实现。原创代码 GPL-3.0-or-later，第三方种子规则保留原归属和许可。
