/**
 * 执行结果模块的展示格式化（列表页与详情页**共用同一份**）。
 *
 * 为什么单独抽文件而不是各页面各写一份：映射率与耗时在两页都会出现，
 * 两处分别实现就会出现"列表显示 85.0%、详情显示 84.97%"这种当面打架的数字，
 * 而这种不一致用户只会归因为"系统在乱算"。
 */

/** 一秒的毫秒数。 */
const MS_PER_SECOND = 1000;

/** 一分钟的毫秒数。 */
const MS_PER_MINUTE = 60 * MS_PER_SECOND;

/**
 * 把时长入参归一成毫秒数。
 *
 * **入参是字符串，而且这是正常输入不是异常**：后端 `JacksonConfig` 对 `Long` 与 `long`
 * 都注册了 `ToStringSerializer`，`durationMs` 在 JSON 里是 `"5297"` 而不是 `5297`
 * （见 `types/execution.ts`）。这里统一 `Number(trim)` 归一，页面就永远不会拿到
 * `NaN` 或 `"123" + "4"` 这种拼接结果。
 *
 * @param value 毫秒的数字字符串（如 `"5297"`）
 * @returns 非负有限数；无法解析时 `null`
 */
function parseMillis(value: string | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  const trimmed = value.trim();
  if (trimmed === '') {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

/**
 * 毫秒 → 人性化耗时。
 *
 * 规则（刻意粗糙，只要"一眼能比大小"）：
 * - `< 1s` → `850ms`
 * - `< 1min` → `1.2s`
 * - 其余 → `2m05s`（分钟数不带小数，秒补零，避免 `2m5s` 读成 `2m 50s`）
 *
 * 所有"时长比较"都必须留在这个函数里（`ms < MS_PER_SECOND` 这种），
 * **页面里不要写 `row.durationMs < 1000`**：字符串比较靠隐式转换，今天能跑，
 * 明天后端换成 `"1.2s"` 就静默错。
 *
 * @param value 毫秒的数字字符串（后端 `Long` → String）。
 *   `null` / `undefined` / 空串 / 无法解析 / 负数 → `-`（绝不显示 `NaNms`）
 * @returns 展示文本
 */
export function formatDurationMs(value: string | null | undefined): string {
  const ms = parseMillis(value);
  if (ms === null) {
    return '-';
  }
  if (ms < MS_PER_SECOND) {
    return `${Math.round(ms)}ms`;
  }
  if (ms < MS_PER_MINUTE) {
    return `${(ms / MS_PER_SECOND).toFixed(1)}s`;
  }
  const minutes = Math.floor(ms / MS_PER_MINUTE);
  const seconds = Math.floor((ms % MS_PER_MINUTE) / MS_PER_SECOND);
  return `${minutes}m${String(seconds).padStart(2, '0')}s`;
}

/**
 * 映射率 → 百分比文本。
 *
 * ⚠️ `mappedRate` 是后端算好的 **0~1 小数**，这里只做 `× 100`，
 * **不做除法**（口径唯一，见 `types/execution.ts` 的说明）。
 *
 * `mappedRate === 0 && totalCount === 0` 时显示 `-` 而不是 `0.0%`：
 * 一批 0 条结果的文件（空报告）映射率是没有定义的，"0.0%" 会被读成
 * "一条都没映射上"，而实际上是"没有可映射的东西"。
 *
 * @param mappedRate 0~1 的映射率
 * @param totalCount 结果总条数
 * @returns 百分比文本或 `-`
 */
export function formatMappedRate(mappedRate: number, totalCount: number): string {
  if (totalCount <= 0 || !Number.isFinite(mappedRate)) {
    return '-';
  }
  return `${(mappedRate * 100).toFixed(1)}%`;
}

/**
 * 映射率的分子分母说明。
 *
 * 只给一个百分比，用户没法判断"差的那 15% 是多少条"，也没法判断分母是
 * 结果总数还是失败数——所以列表的 tooltip 与详情的大字下面都要带上这句。
 *
 * @param mappedCount 已映射条数
 * @param totalCount 结果总条数
 * @returns 形如 `17 / 20 条结果映射到了用例`
 */
export function describeMappedRate(mappedCount: number, totalCount: number): string {
  return `${mappedCount} / ${totalCount} 条结果映射到了用例`;
}
