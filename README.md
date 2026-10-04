# 本地订阅计费与用量计量（Subscription Billing, Local）

一套**完全本地运行**的订阅计费与用量计量能力：订阅生命周期与套餐变更、用量上报与
聚合、账单生成与收款催收，以及**账期结清后迟到补报的补差调整闭环**。所有数据保存在
内存存储中，支付由本地模拟器完成，
**不依赖任何外部支付服务、真实凭据或网络资源**。

技术栈：Java 21 / Spring Boot 3.4（核心域为纯 Java，无框架侵入），JUnit 5 测试。

---

## 1. 核心概念与数据模型

| 类型 | 说明 |
| --- | --- |
| `Plan` | 套餐：月费 `monthlyPriceCents`（分）、整月包含额度 `includedUnits`、超用阶梯 `OverageTier` |
| `Subscription` | 订阅聚合根：状态（ACTIVE/CANCELLED）、当前账期 `[periodStart, periodEnd)`、套餐分段 `segments`、乐观版本 `version` |
| `PlanSegment` | 账期内套餐分段 `[start, end)`；`end=null` 为开口段。分段不重叠、无缝隙 |
| `PlanChange` | 只增不改的变更审计：CREATE / UPGRADE / DOWNGRADE / RENEW / CANCEL |
| `UsageEvent` | 用量事件，`eventId` 全局幂等键，含发生时间 `occurredAt` 与上报时间 `reportedAt` |
| `Invoice` | 账单：套餐费 + 用量费 + 总额；`kind` 区分 REGULAR（账期出账）与 ADJUSTMENT（结清后补差）；状态 PENDING / PAID / DUNNING / FAILED |
| `Invoice.Recalculation` | 未结清账单因迟到补报触发的金额重算审计：前后金额、新增用量、引发事件 |
| `ReconciliationStatement` | 对账结论：原账单金额 + 补差合计 = 当前口径应收总额，差额必须为零 |
| `RetryPolicy` | 收款重试策略：首次扣款 + 若干重试间隔（默认 1 天 / 3 天 / 7 天，共 4 次尝试） |

金额单位统一为**分（long）**，时间单位为**毫秒时间戳**。账期固定为 **30 天**，
自订阅创建时刻对齐滚动（确定规则，无时区/历法漂移）。

预置套餐目录（`PlanRepository`）：

| 套餐 | 月费（分） | 包含额度 | 超用阶梯（对“超出额度后的第 N 个单位”） |
| --- | ---: | ---: | --- |
| BASIC | 1000 | 100 | 第 1–100 个 5 分/个；第 101 个起 3 分/个 |
| PRO | 3000 | 500 | 第 1–500 个 4 分/个；第 501 个起 2 分/个 |
| ENTERPRISE | 8000 | 2000 | 全部 2 分/个 |

---

## 2. 计价与比例分摊规则

### 2.1 周期内变更的比例分摊

周期中（升级、降级、取消）变更在 `effectiveAt` 处“关闭旧开口段、开新段”。
对账单账期 `[P0, P1)`（时长 `P`）与某分段交集 `[lo, hi)`（时长 `L`）：

```
分摊比例 r      = L / P
分段套餐费     = round_half_up(月费 × r)          // 单位：分
分段包含额度   = round_half_up(整月包含额度 × r)  // 单位：整数用量
```

- 取整统一为**非负 HALF_UP**（`round(num/den) = (2·num + den) / (2·den)`），
  全月各段费用之和与整月费用的差不超过 1 分，规则确定、可复现；
- 取消订阅：开口段在取消时刻闭合，只为实际服务时长计费；
- 续订（账期滚动）：跨越账期边界的分段在边界处切分，各账期比例独立闭合可核对；
- **补报式变更**（变更提交晚、生效时间早）允许落在当前账期内：系统以生效时间为界
  截断其后的分段，最终分段序列只反映每个生效点的最新意图，重复提交不会叠加费用。

### 2.2 阶梯用量计价

```
超用量 overage = max(0, 账期内去重总用量 − Σ各分段比例包含额度)
用量费 = Σ 阶梯覆盖单位数 × 阶梯单价     // 分段累进（progressive），按账期末开口段套餐的阶梯
总额   totalCents = planFeeCents + usageFeeCents   // 守恒：任何落盘账单均满足；
                                  // PAID 后金额冻结，未结清前可被迟到补报重算（见 4.4）
```

示例：整月 BASIC 用量 250 → 超用 150 → `100×5 + 50×3 = 650` 分，总额 `1000+650=1650`。
第 15 天 BASIC→PRO 且用量 350：套餐费 `500+1500=2000`，比例额度 `50+250=300`，
超用 50 按 PRO 首档 `50×4=200`，总额 `2200`（见 `BillingServiceTest`）。

---

## 3. 用量聚合语义

- **幂等去重**：`eventId` 为全局唯一键，仓储层原子 `putIfAbsent`。重复上报以
  `DUPLICATE_EVENT` **明确拒绝**（可区分的错误码），绝不静默丢弃、绝不重复计入；
- **按发生时间归集**：聚合只看 `occurredAt`，与上报先后无关。乱序（先晚后早）、
  迟到（账期内事件晚些补报）事件都会进入同一口径；
- **迟到边界**：`occurredAt` 不得早于订阅创建时间（`EVENT_TOO_OLD`），
  不得晚于当前时间 + 60 天容忍窗口（`EVENT_OUT_OF_ORDER_WINDOW`）；
- **稳定口径**：窗口 `[from, to)` 内事件按 `(occurredAt, eventId)` 排序后求和，
  仅依赖已存事件集合。同一查询执行任意次、或在补报后重新聚合，结果一致可复现；
- **账单侧联动**：事件被接受后自动通知账单侧——落在已出账账期的迟到事件会触发
  未结清重算或结清后补差（见 4.4），不会只进入汇总就结束。

---

## 4. 账单、催收与幂等约束

### 4.1 出账

- 一个订阅的一个账期**只允许一张 REGULAR 账单**（`(subscriptionId, periodStart)` 唯一索引，
  在订阅级锁内检查）。账期未结束出账被拒绝（`INVOICE_PERIOD_CONFLICT`）；
- 账单合并套餐费与用量费；**结清（PAID）后金额冻结**，未结清前可被迟到补报触发重算（见 4.4）；
- 已支付账期禁止重新生成（迟到用量由补差调整单承载，见 4.4）。

### 4.2 收款确认幂等

- `confirmPayment`：`PAID` 账单重复确认**原样返回且不调用支付网关**（零副作用）；
  `FAILED` 终态再确认 → `INVOICE_NOT_CONFIRMABLE`；
- 真实扣款只在订阅级互斥锁内、账单为 PENDING/DUNNING 时发生，每次尝试记录
  不可变审计（金额、序号、网关引用），测试可断言“只有一次真实扣款”。

### 4.3 催收（Dunning）

```
首次扣款失败 -> DUNNING，attempt=1，nextRetryAt = now + intervals[0]
重试未到期   -> RETRY_NOT_DUE（不扣款）
到期重试成功 -> PAID（之后所有入口幂等、零扣款）
到期再失败   -> 调度下一次；attempt 达到 maxAttempts -> FAILED
FAILED 再重试-> RETRY_EXHAUSTED
```

`RetryPolicy` 可配置（间隔列表，`maxAttempts = 间隔数 + 1`）。支付由
`LocalPaymentGateway` 本地模拟：测试用 `prime(invoiceId, success, reason)`
逐次编排成功/失败，不访问任何外部资源。

### 4.4 迟到补报：重算与补差调整单

用量事件被接受后（`UsageService` 钩子自动触发，也可显式调用
`BillingService.processLateUsage`），按 `occurredAt` 定位所属账期并分流：

```
账期尚未出账        -> 无需处理（出账时按当前事件口径自然计入）
账单未结清          -> 按当前已存事件整体重算并更新账单金额，
  (PENDING/DUNNING/    重算审计（前后金额、新增用量、引发事件ID）记入
   FAILED)             recalculations；金额不变则零副作用
账单已结清 (PAID)   -> 原账单金额冻结，生成 ADJUSTMENT 补差调整单：
                       补差 = 重算应收总额 − (原账单 + 已有补差合计)
```

补差调整单规则：

- **明确关联**：`linkedInvoiceId` 指向原账单，`periodStart/periodEnd` 与原账期一致；
  `deltaUnits` 记录本次新增用量，`basisEventIds` 记录计价依据的补报事件，
  日志打印完整计价规则（重算总额、已计费额、差额）；
- **同权处理**：ADJUSTMENT 与 REGULAR 共用收款确认、催收重试、查询与对账链路，
  状态机完全一致；
- **不凭空出单**：无新增迟到事件、或新增用量仍在已计费口径内（差额为零）时不生成；
  同一批补报无论重复触发多少次、与收款操作如何交错并发（订阅级锁串行化），
  都只生成一张补差、只扣款一次，重复确认零副作用；
- **可受理边界**：`occurredAt` 早于订阅创建（`EVENT_TOO_OLD`）或超出未来 60 天
  容忍窗口（`EVENT_OUT_OF_ORDER_WINDOW`）的补报仍被明确拒绝，不产生任何补差痕迹。

### 4.5 对账（Reconciliation）

`BillingService.reconcile(subscriptionId, periodStart)` 对任一已出账账期给出：

```
原账单金额 + 已产生的补差金额 = 按当前已存事件重算的应收总额
discrepancy = 重算总额 − (原账单 + 补差合计)  == 0   （必须为零且可复现）
```

对账与出账/重算/补差共用同一计价口径（`computeCharges`：分段比例费 + 阶梯超用费，
只依赖套餐分段与已存事件集合），因此任意时刻、任意次数查询结果一致；
账期无原账单时拒绝（`INVOICE_NOT_FOUND`）。

---

## 5. 并发一致性

- 每个订阅 ID 一把互斥锁（`SubscriptionRepository.withLock` / `InvoiceRepository.withLock`），
  同一订阅的套餐变更、收款操作与迟到补报处理（重算/补差）各自串行化，消除重复计费窗口；
- 变更携带 `expectedVersion` 乐观检查：版本过期 → `CONCURRENT_MODIFICATION`，
  失败操作不推进版本、不改动分段（无丢失更新）。并发测试见
  `SubscriptionServiceTest.concurrentChange_onlyOneSucceeds`（8 线程并发仅 1 次成功）。

## 6. 拒绝/失败原因（可区分错误码）

`PLAN_NOT_FOUND`、`SUBSCRIPTION_NOT_FOUND`、`ILLEGAL_STATE_TRANSITION`、
`PLAN_UNCHANGED`、`EFFECTIVE_TIME_INVALID`、`DOWNGRADE_QUOTA_EXCEEDED`、
`CONCURRENT_MODIFICATION`、`DUPLICATE_EVENT`、`EVENT_TOO_OLD`、
`EVENT_OUT_OF_ORDER_WINDOW`、`QUANTITY_INVALID`、`INVOICE_NOT_FOUND`、
`INVOICE_PERIOD_CONFLICT`、`INVOICE_ALREADY_PAID`、`INVOICE_NOT_CONFIRMABLE`、
`PAYMENT_DECLINED`、`RETRY_NOT_DUE`、`RETRY_EXHAUSTED`。

测试日志对每个场景打印**输入、关键状态与判定依据**（`[pricing]`/`[subscription]`/
`[usage]`/`[billing]`/`[dunning]`/`[gateway]` 前缀，以及测试中的 `[case]` 行）。

---

## 7. 本地验证方法

前置：JDK 21+、Maven 3.9+（无需数据库、无需网络）。

```bash
# 编译
mvn -q -DskipTests compile

# 运行全部单元测试（29 个用例，秒级完成）
mvn test

# 只看某个测试类
mvn -Dtest=BillingServiceTest test

# 控制台端到端演示（确定性脚本：升降级比例费、重复/乱序用量、
# 账单合并、重复确认幂等、失败->催收->重试成功/耗尽、
# 结清后迟到补报->补差调整单->收款->对账平衡）
mvn -q exec:java -Dexec.mainClass=com.github.highcumontoa.subscriptionbillingjava.demo.LocalBillingDemo
```

测试覆盖矩阵：

| 测试类 | 覆盖场景 |
| --- | --- |
| `PricingServiceTest` | 半月/三等分比例守恒、HALF_UP 取整、阶梯累进、纯函数可复现 |
| `SubscriptionServiceTest` | 非法状态流转、可区分拒绝码、降级配额拒绝、8 线程并发变更、补报式重切分、续订 |
| `UsageServiceTest` | 重复事件、乱序、迟到补报、非法输入、8 线程并发同一 eventId |
| `BillingServiceTest` | 额度内/超用/周期中升级账单、总额守恒、账期唯一、重复确认仅扣款一次 |
| `DunningServiceTest` | 首跳失败入催收、未到期拒绝、到期重试成功后幂等、重试耗尽 FAILED |
| `LateUsageSettlementTest` | 结清后补报生成补差并收款、重复/并发触发不重复计费、未结清补报重算与汇总一致、无新增不出单、超窗补报可区分拒绝 |

> 说明：核心域服务是无 Spring 依赖的普通 Java 类，测试用 `ManualClock`
> 固定/推进时间，保证所有结论确定、可复现。
