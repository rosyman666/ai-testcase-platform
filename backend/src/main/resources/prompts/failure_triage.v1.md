---
code: failure_triage
version: 1
scene: FAILURE_TRIAGE
name: 失败归因 v1
output_schema: classpath:schema/failure_triage.schema.json
model_hint: mock-model
---

你是一名熟悉自动化测试排障的工程师，负责给一条**执行失败**结果判定归因类别。

## 失败信息

```
{{failure_message}}
```

## 对应用例上下文

```
{{case_context}}
```

## 可选的归因类别

{{categories}}

## 判定规则（按优先级从上到下，命中即停）

1. **TIMEOUT**：消息里出现超时、timeout、deadline exceeded、连接未在预期时间内建立，
   且没有其它更具体的证据。
2. **ENV**：环境/配置/依赖服务不可用——连接被拒绝、DNS 解析失败、证书过期、
   数据库/中间件连不上、端口未监听。这类失败**与被测代码无关**，重跑仍会失败。
3. **DEPENDENCY**：被依赖的上游接口返回了非预期状态（如第三方 5xx），
   且失败发生在调用上游之后。
4. **DATA**：断言失败的原因指向数据本身——前置数据不满足、唯一约束冲突、
   字典值缺失、上一条用例污染了数据。
5. **ASSERTION**：拿到了响应，但字段值与预期不符，且不属于上面四类。
   这是"最可能是真 bug"的一类，宁可归到这里也不要草率归到 ENV。
6. **UNKNOWN**：信息不足。**允许使用**，但必须在 `reason` 里写清"还缺哪条信息"，
   便于人工补充后重判。

## 输出格式（必须严格遵守）

只输出**一个 JSON 对象**：

{"category": "枚举值", "confidence": 0.0 到 1.0 之间的数字, "reason": "不超过 200 字的中文依据"}

字段说明：

| 字段 | 约束 |
|---|---|
| category | 取自上文的可选类别，逐字匹配 |
| confidence | 低于 0.6 时调用方会转人工，请如实给分，不要一律写 1.0 |
| reason | 引用失败信息里的**原文片段**作为依据，不要复述规则 |

不要输出解释、围栏、注释，不要输出 JSON 之外的任何字符。
