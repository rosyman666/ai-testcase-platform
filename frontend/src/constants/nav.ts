/**
 * 侧边导航的**唯一真源**。
 *
 * 为什么单独抽成常量而不是写在 SideNav 里：
 * 1. 侧栏（SideNav）与顶栏（TopBar）都要知道"当前在哪个模块"，两边各写一份必然漂移；
 * 2. 纯数据 + 纯函数可以被 `navMatch.test.ts` 直接单测，不需要渲染 MUI 组件树；
 * 3. 以后加模块只改这张表，不用去动布局组件。
 */

import SvgIcon from '@mui/material/SvgIcon';
import ChecklistRounded from '@mui/icons-material/ChecklistRounded';
import DescriptionRounded from '@mui/icons-material/DescriptionRounded';
import FactCheckRounded from '@mui/icons-material/FactCheckRounded';
import InsightsRounded from '@mui/icons-material/InsightsRounded';
import PlayCircleOutlineRounded from '@mui/icons-material/PlayCircleOutlineRounded';
import SpaceDashboardRounded from '@mui/icons-material/SpaceDashboardRounded';
import TuneRounded from '@mui/icons-material/TuneRounded';

/**
 * 图标组件的类型。
 *
 * 说明：`@mui/icons-material` 内部用 `type SvgIconComponent = typeof SvgIcon` 表达图标类型，
 * 但**并没有把这个 type 导出**（v5.16 的 index.d.ts 里是模块内私有声明），
 * 所以这里用同样的方式在本地复刻一份，避免 `import type { SvgIconComponent } from '@mui/icons-material'`
 * 触发 TS2305（模块没有导出的成员）。
 */
export type NavIcon = typeof SvgIcon;

/** 一个导航项。 */
export interface NavItem {
  /** 稳定标识，与文案解耦，供选中判定与测试断言使用。 */
  key: string;
  /** 侧栏文案。 */
  label: string;
  /** 目标路径（同时也是最长前缀匹配的基准）。 */
  path: string;
  /** 图标组件。 */
  icon: NavIcon;
  /** true 表示只有 `pathname` 完全相等才算选中（用于 `/` 这类根路径）。 */
  exact?: boolean;
}

/**
 * 导航项列表（顺序即侧栏展示顺序）。
 *
 * `overview` 必须 `exact: true`：否则 `pathname.startsWith('/')` 恒为真，
 * 所有页面都会把「概览」点亮。
 */
export const NAV_ITEMS: NavItem[] = [
  { key: 'overview', label: '概览', path: '/', icon: SpaceDashboardRounded, exact: true },
  { key: 'docs', label: '需求文档', path: '/docs', icon: DescriptionRounded },
  // 测试点评审紧跟在需求文档后面：它是文档链路的下一跳（选文档 → 评审测试点），
  // 侧栏顺序刻意与业务流程一致，而不是按开发批次排
  { key: 'testpoints', label: '测试点评审', path: '/testpoints', icon: ChecklistRounded },
  { key: 'prompts', label: '提示词模板', path: '/prompts', icon: TuneRounded },
  { key: 'tasks', label: '生成任务', path: '/tasks', icon: PlayCircleOutlineRounded },
  { key: 'cases', label: '用例评审', path: '/cases', icon: FactCheckRounded },
  { key: 'metrics', label: '指标看板', path: '/metrics', icon: InsightsRounded },
];

/**
 * 判断单个导航项是否命中给定路径。
 *
 * @param item 导航项
 * @param pathname 当前路径（`useLocation().pathname`）
 * @returns 命中返回 true
 */
function isHit(item: NavItem, pathname: string): boolean {
  // 根路径必须精确匹配，否则 `/` 会把全站都吃掉
  if (item.exact) {
    return pathname === item.path;
  }
  // 前缀匹配必须拼上 `/`：只比 startsWith(path) 会让 `/tasks` 误命中 `/tasksomething`
  return pathname === item.path || pathname.startsWith(`${item.path}/`);
}

/**
 * 按当前路径找出命中的导航项。
 *
 * 多条命中时取 `path.length` 最长者，保证以后出现 `/tasks/new` 这类
 * 比父级更具体的导航项时，选中态落在更具体的那一条上。
 *
 * @param pathname 当前路径（`useLocation().pathname`）
 * @returns 命中的导航项；都不命中返回 undefined（例如 404 兜底路径）
 */
export function matchNavItem(pathname: string): NavItem | undefined {
  let matched: NavItem | undefined;

  for (const item of NAV_ITEMS) {
    if (!isHit(item, pathname)) {
      continue;
    }
    if (matched === undefined || item.path.length > matched.path.length) {
      matched = item;
    }
  }

  return matched;
}

/** 顶栏在完全匹配不到时的默认标题。 */
export const FALLBACK_MODULE_TITLE = '控制台';

/**
 * 不在侧栏导航里、但顶栏需要正确标题的页面（正则 → 标题，按顺序取第一条命中）。
 *
 * 目前**没有条目**：`/testpoints` 已经是导航项（T12-2a 补的落地页），
 * `/testpoints/:docId` 由 `matchNavItem` 的前缀规则命中「测试点评审」，
 * 所以原来的 `/^\/testpoints\/[^/]+$/` 已成死代码，删掉。
 *
 * 为什么保留这张空表而不是把循环一起删掉：以后再加"有页面、但侧栏故意不给入口"的
 * 详情页（例如独立的全屏编辑页）时，只需往这里 push 一条，不必改
 * `resolveModuleTitle` 的签名，也不用改 TopBar。
 *
 * 注意：`/docs/:id` **不要**往这里加——它会被 `matchNavItem` 前缀命中「需求文档」，
 * 这本来就是期望值；具体文档名由详情页自己的 `PageHeader` 展示。
 */
const EXTRA_TITLES: ReadonlyArray<readonly [RegExp, string]> = [];

/**
 * 解析顶栏显示的业务模块标题。
 *
 * 顺序：导航项 label → 额外标题表 → 「控制台」。
 * 收在这里而不是写在 TopBar 里，是为了能纯函数单测（`/testpoints/:docId`
 * 这类"有页面、但侧栏没有入口"的情况最容易被漏掉）。
 *
 * @param pathname 当前路径（`useLocation().pathname`）
 * @returns 模块标题，永不为空串
 */
export function resolveModuleTitle(pathname: string): string {
  const navItem = matchNavItem(pathname);
  if (navItem) {
    return navItem.label;
  }

  for (const [pattern, title] of EXTRA_TITLES) {
    if (pattern.test(pathname)) {
      return title;
    }
  }

  return FALLBACK_MODULE_TITLE;
}
