# MetroPass

Metro 月票 / 学生票插件：购买后 **30 天内乘车不花钱**（月票 100、学生票 50，均可配置）。

- 支持 **续费叠加**（未过期时购买会从当前到期时间顺延）；
- 完整的管理员工具（发放 / 移除 / 查询 / 统计）；
- 全部提示文本可配置，默认中文。

## 工作原理（垫付-报销）

Metro 1.x 没有任何票价拦截点：扣费在"玩家上车成功之后"由 `TicketService` 直接执行，
附属插件既拿不到扣费事件、也无法豁免金额。MetroPass 采用如下纯插件方案（不改本体）：

| 阶段 | 时机 | 动作 |
| ---- | ---- | ---- |
| 垫付 | 右键车站铁轨 / 在乘车 GUI 点击线路（均早于 Metro 的余额检查） | 把玩家余额补足到"该站最低票价估算 + 缓冲"，避免余额不足被拒载 |
| 报销 | 扣费完成后（上车后 1 tick / 到站 DOCKED / 下车后 2 tick / 离线） | 比对观察窗口的前后余额差量，把 Metro 扣走的车费**原额退还** |
| 回收 | 上车结算完成后 / 会话结束（下车、超时、离线） | 收回垫付的临时资金，只保留玩家自有余额 |

> 由于 Metro 的"已扣费 $X"提示无法拦截，玩家会先看到扣费消息，紧接着收到
> "月票已覆盖本次车费，已原路退还"的提示（可在 config 中关闭提示）。

### 经济模型

- 购票：`withdraw`（钱从玩家账户扣除，等价于系统回收）；
- 报销：`deposit`（按实际乘坐金额发放）。

建议月票定价覆盖预期乘车成本（`/metropass admin stats` 可查看"累计票款 vs 累计报销"用于调价）。
所有金额操作均有单次上限保护（`fare-guard.max-refund-per-segment` / `max-reserve`），
异常情况会写入控制台告警。

## 安装

1. 服务端：Paper / Spigot **1.18.2+**；已安装 **Metro 1.1.9**（其他版本未经核对）与 **Vault + 经济插件**。
2. 将 `metro-pass-2.0.0.jar` 放入 `plugins/`。
3. 重启服务器。控制台应出现：
   `FareGuard 已启用：持票玩家乘车产生的车费将通过垫付-报销方式全额返还。`

> 若未出现该行，请检查日志中的 `FareGuard` 警告（通常是 Metro 版本不符或缺少 Vault 经济）。

## 命令与权限

| 命令 | 说明 | 权限 |
| ---- | ---- | ---- |
| `/metropass buy monthly` | 购买/续费月票（未过期顺延 30 天） | `metropass.use`（默认所有人） |
| `/metropass buy student` | 购买/续费学生票 | 同上 |
| `/metropass status` | 查看剩余时间 | 同上 |
| `/metropass admin give <玩家> <monthly\|student> [天数]` | 发放 | `metropass.admin`（默认 OP） |
| `/metropass admin remove <玩家> [monthly\|student]` | 移除 | 同上 |
| `/metropass admin info <玩家>` | 查询 | 同上 |
| `/metropass admin stats` | 售票/报销统计 | 同上 |
| `/metropass admin reload` | 重载配置 | 同上 |

别名：`/mpass`。

## 配置（config.yml）

```yaml
prices:
  monthly: 100.0       # 月票价格
  student: 50.0        # 学生票价格
duration-days: 30      # 每张票的有效期（天）

fare-guard:
  enabled: true        # 免费乘车总开关（false = 只记录票，乘车照常付费）
  max-refund-per-segment: 10000.0  # 单次报销上限（安全阀）
  max-reserve: 10000.0             # 单次垫付上限（安全阀）
  reserve-buffer: 1.0              # 垫付缓冲
  session-timeout-seconds: 15      # 垫付会话超时（未乘车则回收）
  notify-refund: true              # 报销到账提示
  debug: false                     # 排查日志
```

数据保存在 `plugins/MetroPass/passes.yml`（旧版写在 config.yml 里的记录会自动迁移）。

## 常见问题

- **聊天里先显示扣费、又退款？** 这是设计使然（见"工作原理"）。Metro 的提示无法拦截/改写。
- **支持 Folia 吗？** 暂不支持（未声明 `folia-supported`）。
- **升级 Metro 怎么办？** FareGuard 依赖 1.1.9 的时序。升级后建议先在测试服验证一遍：
  正常情况是"扣费后立即退款"；若 Metro 大改流程导致未能退款，最坏结果为乘客正常付费
  （不存在多扣/重复扣款），可在 config 中关闭 `fare-guard` 过渡。
- **和 metro-altroutes 冲突吗？** 不冲突：altroutes 负责"检修线路拦车"，MetroPass 只在扣费前后做资金对冲。

## 构建

需要 **JDK 21+**（验证于 JDK 25）与 Maven 3.9+：

```
mvn package
```

`libs/metro-1.1.9.jar` 为 Metro 官方 jar（上游未发布到公共仓库，做法与 metro-altroutes 一致），
升级 Metro 时替换该文件并同步修改 `pom.xml` 中的版本号。

### 自动构建（GitHub Actions）

仓库内置两套工作流，推送后自动生效：

- **CI**（`.github/workflows/ci.yml`）：push / PR 时自动编译并运行单元测试，
  构建产物 `metro-pass-*.jar` 可在 Actions 页面的 Artifacts 中下载；
- **Release**（`.github/workflows/release.yml`）：推送 `v*` 标签（如 `v2.0.0`）后
  自动创建 GitHub Release 并附带插件 jar。

本地打标签发布：

```
git tag -a v2.0.0 -m "MetroPass v2.0.0"
git push origin main --tags
```

## 变更日志

### 2.0.0
- 重写"免费乘车"为**不修改 Metro 本体**的垫付-报销机制（原 1.0.0 依赖不存在的 `MetroFareEvent`，无法编译与运行）；
- 新增续费叠加、管理员命令（give/remove/info/stats/reload）、统计审计；
- 票据数据迁移至独立 `passes.yml`，过期自动清理；
- 全部消息可配置，默认中文。
