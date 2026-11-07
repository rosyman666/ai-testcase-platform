---
code: case_repair
version: 1
scene: CASE_REPAIR
name: 输出修复 v1
output_schema: classpath:schema/testcase.schema.json
model_hint: mock-model
---

你是一名严格的数据修复器。上一次的输出**不合法**，请只做修复，不要重新创作。

## 上一次的原始输出

```
{{original_output}}
```

## 校验失败的原因（这是唯一的修复依据）

```
{{validation_error}}
```

## 必须满足的 JSON Schema

```
{{schema}}
```

## 修复规则

1. **只修不合法的部分**：结构、类型、字段名、枚举值、缺失字段、语法（尾逗号/单引号/围栏）。
   内容本身——用例的步骤与预期——**保持原样**，不要"顺便改写得更专业"。
2. **不要新增未要求的字段**，也不要删除已合法的字段。
3. **枚举值必须逐字匹配 schema**：`priority` 只能用 P0/P1/P2/P3；`caseType` 只能用
   FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY。中文描述放到 `title` 里。
4. **如果原输出被截断**（最后一个元素不完整）：丢弃那个不完整的元素，把前面完整的元素补齐闭合，
   **不要凭空补一个完整元素**——补出来的内容是模型编的，会直接污染标注数据。
5. 若原输出完全无法修复（例如只有一个开括号），返回空数组 `{"cases": []}`，
   并让调用方走"宽松降级"分支；**不要**返回一段解释文字。

## 输出格式

只输出**一个 JSON 对象**：`{"cases": [ ... ]}`，字段与原输出一致，不得包含任何解释。
不要用 markdown 围栏包裹，不要加注释或尾逗号。
