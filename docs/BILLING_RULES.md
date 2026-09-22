# 本地订阅计费与用量计量 — 规则说明与本地验证

本服务在**单机内存**中模拟套餐、订阅、用量事件、账单与收款全流程，
不依赖任何外部支付服务、真实凭据或网络资源。所有金额以**分（long）**存储，
比例计算使用 `BigDecimal` + `HALF_UP` 取整到分，结果确定、可复现。

## 1. 账期模型

- 创建订阅的时刻 `anchor` 为账期锚点（UTC），账期按月对齐：
  `账期 n = [anchor + n 个月, anchor + n+1 个月)`。
- 每个订阅在任一时刻只维护当前账期；账期内的每次套餐变更把账期切成多个
  **套餐片段（Segment，append-only）**，片段不可变，是所有结算与审计的依据。

## 2. 套餐与计价

种子套餐（可在 `DataInitializer` 调整）：

| 套餐 | 月基础费 | 账期内含配额 | 超额阶梯（单位单价：分） |
|---|---:|---:|---|
| BASIC | 1,000 | 1,000 | (0, 1000]：3；(1000, 5000]：2；>5000：1 |
| PRO | 5,000 | 5,000 | (0, 5000]：2；>5000：1 |
| SMART | 8,000 | 20,000 | 全部：2（用于演示“跨档直降被拒绝但配额满足”） |
| ENTERPRISE | 15,000 | 20,000 | 全部：1 |

### 2.1 比例分摊（账期内变更 / 取消）

- 片段基础费 = `月费 × 片段秒数 / 账期秒数`，`HALF_UP` 到分
  （`Money.prorate` / `ProrationCalculator`）。
- 示例：4 月 1 日 00:00 UTC 订阅 BASIC（1000 分/月，账期 2,592,000 秒），
  4 月 16 日 00:00 升级 PRO（5000 分/月，恰好半账期）：
  - BASIC 片段费 = 500 分；PRO 片段费 = 2500 分；账单基础费合计 **3000 分**。
- 变更流水上的 `proratedDeltaCents`（仅基础费预估）：
  `(新套餐月费 − 旧套餐月费) × 剩余秒数 / 账期秒数`，供用户当场核对；
  **最终金额以账期账单为准**，流水不产生独立扣款，杜绝重复计费。
- 立即取消：最后一个片段在取消时刻截断，费用只算到取消时刻（如 4 月 11 日
  取消 BASIC → 10/30 账期 → 333 分 < 1000 分）。
- 每个片段的含免配额同样按秒数比例缩放（**向下取整**，保证各片段配额之和
  不超过整账期配额）：`片段配额 = 套餐配额 × 片段秒数 / 账期秒数`。

### 2.2 用量超额阶梯计价

- 片段超额量 = `max(0, 片段内用量 − 片段缩放配额)`。
- 按**边际阶梯**逐档计价（`UsageAggregator.tieredFee`）：
  例如 BASIC 超额 3000 → 1000×3 + 2000×2 = **7000 分**；超额 7000 →
  1000×3 + 4000×2 + 2000×1 = **13000 分**。
- 账单明细按片段顺序产生稳定 key（`BASE-i`、`OVERAGE-i`），
  且 **账单总额严格等于全部明细之和**（守恒），见 `BillAssembler`。

## 3. 用量聚合语义

- `eventId` 全局幂等键：重复上报（含并发）返回
  `DUPLICATE_USAGE_EVENT`，不会重复计入，也不会静默成功。
- 事件按 **occurredAt（发生时间）** 归属账期与片段；
  **乱序、迟到**事件（receivedAt 晚于其他事件）只要仍在账期内、账单未关闭，
  一律接收；账单为 DRAFT 时可反复重算，聚合结果与事件到达顺序无关
  （`UsageRepository` 查询按 occurredAt,eventId 排序），口径稳定可复现。
- 数量必须为正：否则 `USAGE_EVENT_BAD_QUANTITY`；
  发生时间晚于当前时间：`USAGE_EVENT_OUTSIDE_PERIOD`。
- 账期账单一旦 **CONFIRMED/PAID**，该账期补报被拒绝：
  `USAGE_LATE_PERIOD_CLOSED`（显式拒绝而非静默丢弃）；
  已确认账单也禁止重算：`PERIOD_ALREADY_CLOSED`。

## 4. 订阅生命周期与并发约束

- 创建：ACTIVE，锚点即当前时间，首片段覆盖整个首账期。
- 变更（升/降级）：在 `sub:{subscriptionId}` 分段锁内执行，
  并支持 `expectedVersion` 乐观版本校验。并发变更时恰好一个成功提交，
  另一个得到 `CONCURRENT_CHANGE_CONFLICT`；version 单调递增、流水只有一条，
  保证**无重复计费、无丢失更新**。
- 非法/受限流转（原因可区分）：
  - `SAME_PLAN_CHANGE`：目标套餐与当前相同；
  - `PLAN_NOT_FOUND` / `SUBSCRIPTION_NOT_FOUND`：引用不存在；
  - `INVALID_EFFECTIVE_TIME`：生效时间不在当前账期或早于当前时间、未到期续订等；
  - `DOWNGRADE_QUOTA_LIMIT`：目标套餐配额低于当前套餐（如 PRO→BASIC）；
  - `DOWNGRADE_NOT_ALLOWED_TIER`：跨档直降（如 ENTERPRISE→SMART/BASIC），
    即使配额满足也拒绝；
  - `SUBSCRIPTION_CANCELLED` / `SUBSCRIPTION_PENDING_CANCEL`：终态/预约态禁止变更。
- 取消：立即（CANCELLED，截断片段）或预约未来时间（PENDING_CANCEL）。
- 续订：仅当前账期到期后允许，关闭旧片段、以账期终点为起点开新账期片段。

## 5. 账单状态机、催收与幂等

状态：`DRAFT → CONFIRMED → PAID / PAYMENT_FAILED ⇄(重试) … → PAID | DUNNING → VOID`

- 每个“订阅 + 账期起点”唯一一张账单（`BillRepository` 双键索引）。
  DRAFT/PAYMENT_FAILED/DUNNING 可重算；CONFIRMED/PAID/VOID 不可重算。
- 确认与收款 `confirmAndCollect(billId, idempotencyKey)`：
  - **同一 idempotencyKey** 的重复确认直接返回既有账单，
    **不新增 PaymentAttempt、不二次扣款**（幂等）；
  - 不同 key 重复确认 → `BILL_ALREADY_CONFIRMED`。
- 收款由 `LocalPaymentGateway` 模拟（可按账单预置成功/失败序列，
  如 `preset(billId, false, false, true)`；默认成功），无任何网络调用。
- 失败后进入 PAYMENT_FAILED 并按 `RetryPolicy` 安排 `nextRetryAt`
  （默认最多 3 次尝试，退避 1 分钟、10 分钟）：
  - 未到点重试 → `PAYMENT_RETRY_TOO_EARLY`；
  - 手动重试 `retryPayment` 与 `DunningService.runRetryCycle()` 走同一逻辑
    和同一把账单锁；扫描只捞到点且 PAYMENT_FAILED 的账单；
  - 次数耗尽 → `DUNNING`（带 dunningNote），再试 → `RETRY_EXHAUSTED`；
  - 已 PAID 的重试幂等返回，不新增尝试。

## 6. 本地验证方法

### 6.1 单元测试（推荐，覆盖全部要求场景）

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| BillingRulesTest | 比例分摊 HALF_UP、边际阶梯计价 |
| UsageAggregationTest | 乱序/迟到聚合一致、片段配额缩放、整账期基础费 |
| ConcurrentPlanChangeTest | 并发变更只提交一次、版本冲突可区分、五类非法流转、账守恒 |
| UsageReportingTest | 重复/乱序/迟到用量、确认后补报拒绝、重算稳定 |
| BillPaymentTest | 重复确认幂等不二次扣款、退避重试、催收耗尽 DUNNING、失败后成功 |
| MidCycleBillingTest | 账期内升级比例费与流水差额、取消截断结算、续订新账期 |

测试日志会打印输入（事件/时刻/金额）、关键状态（status/version/attempts）
与判定依据（期望错误码、理论金额）。

### 6.2 HTTP 手工验证（可选）

```bash
mvn spring-boot:run
# 1) 建订阅
curl -s -X POST localhost:8080/local/subscriptions -H 'Content-Type: application/json' \
  -d '{"planCode":"BASIC"}'
# 2) 推进时钟（本地演示用）
curl -s -X POST localhost:8080/local/time/travel -H 'Content-Type: application/json' \
  -d '{"at":"2026-04-16T00:00:00Z"}'
# 3) 账期内升级（带 expectedVersion 乐观锁）
curl -s -X POST localhost:8080/local/subscriptions/{id}/change -H 'Content-Type: application/json' \
  -d '{"targetPlanCode":"PRO","expectedVersion":0}'
# 4) 上报用量
curl -s -X POST localhost:8080/local/usage -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","subscriptionId":"{id}","quantity":1300,"occurredAt":"2026-04-02T00:00:00Z"}'
# 5) 生成/重算账单、确认收款（幂等键）
curl -s -X POST 'localhost:8080/local/subscriptions/{id}/bills?periodIndex=0'
curl -s -X POST localhost:8080/local/bills/{billId}/confirm -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"k-1"}'
# 6) 到退避时间后催收扫描
curl -s -X POST localhost:8080/local/dunning/run
```

业务拒绝统一返回 HTTP 422，体含可区分的 `errorCode` 与 `message`。
