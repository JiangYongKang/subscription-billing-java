# subscription-billing-java（本地订阅计费与用量计量）

纯本地内存实现的订阅计费演示：订阅生命周期、账期内套餐变更比例结算、
用量幂等聚合与阶梯计价、账单生成与收款失败重试/催收，全部不依赖外部支付服务。

## 快速开始

```bash
mvn test            # 17 个单测/集成测试，覆盖并发变更、重复乱序用量、重复确认、收款重试
mvn spring-boot:run # 可选：启动本地 HTTP 接口（端口 8080）
```

完整的计价/比例分摊规则、用量聚合语义、催收与幂等约束、HTTP 手工验证步骤见
[`docs/BILLING_RULES.md`](docs/BILLING_RULES.md)。

## 代码结构

| 包 | 职责 |
|---|---|
| `model` | 套餐、订阅、片段、用量事件、账单、重试策略等值对象/枚举 |
| `billing` | 时钟、金额分摊、阶梯计价、用量聚合、账单装配（总额守恒） |
| `store` / `store.lock` | 内存仓储与按业务键分段的 `ReentrantLock` |
| `service` | 订阅/用量/账单/催收服务与本地模拟支付网关 |
| `error` | 统一 `BillingException` + 可区分的 `ErrorCode` |
| `web` | 本地验证用 REST 接口与 422 错误处理 |
| `config` | Bean 装配、默认重试策略、种子套餐 |
