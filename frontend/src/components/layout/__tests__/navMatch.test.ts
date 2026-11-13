import { describe, it, expect } from 'vitest';
import {
  FALLBACK_MODULE_TITLE,
  matchNavItem,
  NAV_ITEMS,
  resolveModuleTitle,
} from '@/constants/nav';

describe('constants/nav — matchNavItem 路径匹配', () => {
  it('根路径命中概览', () => {
    expect(matchNavItem('/')?.key).toBe('overview');
  });

  it('列表页精确命中自身', () => {
    expect(matchNavItem('/docs')?.key).toBe('docs');
    expect(matchNavItem('/tasks')?.key).toBe('tasks');
    expect(matchNavItem('/metrics')?.key).toBe('metrics');
  });

  it('详情页按前缀命中父级列表（/docs/123）', () => {
    expect(matchNavItem('/docs/123')?.key).toBe('docs');
  });

  it('多级路径仍命中最近的一级（/docs/123/sections）', () => {
    expect(matchNavItem('/docs/123/sections')?.key).toBe('docs');
  });

  it('任务详情按前缀命中任务列表（/tasks/abc）', () => {
    expect(matchNavItem('/tasks/abc')?.key).toBe('tasks');
  });

  it('/testpoints/:docId 按前缀命中测试点评审（T12-2a 起它有了导航项）', () => {
    // T12-1b 时测试点评审没有落地页、也没进导航表，这条断言是"不命中"；
    // T12-2a 补了 /testpoints 落地页与导航项后，详情路径由前缀规则命中，断言随之翻转
    expect(matchNavItem('/testpoints/1')?.key).toBe('testpoints');
    expect(matchNavItem('/testpoints')?.key).toBe('testpoints');
  });

  it('未知路径返回 undefined', () => {
    expect(matchNavItem('/unknown')).toBeUndefined();
  });

  it('前缀不缺分隔斜杠：/tasksomething 不得命中 /tasks', () => {
    // 这是最容易写错的一条：只用 startsWith(path) 会误命中
    expect(matchNavItem('/tasksomething')).toBeUndefined();
    expect(matchNavItem('/docsx')).toBeUndefined();
  });

  it('空串返回 undefined', () => {
    expect(matchNavItem('')).toBeUndefined();
  });

  it('overview 标记为 exact，其余不是', () => {
    const overview = NAV_ITEMS.find((item) => item.key === 'overview');
    expect(overview?.exact).toBe(true);
    expect(NAV_ITEMS.filter((item) => item.exact === true)).toHaveLength(1);
  });

  it('导航表恰好 7 项，覆盖 6 个业务模块 + 概览', () => {
    expect(NAV_ITEMS).toHaveLength(7);
    expect(NAV_ITEMS.map((item) => item.key)).toEqual([
      'overview',
      'docs',
      'testpoints',
      'prompts',
      'tasks',
      'cases',
      'metrics',
    ]);
  });

  it('导航表的 key 与 path 唯一，避免选中态出现歧义', () => {
    expect(new Set(NAV_ITEMS.map((item) => item.key)).size).toBe(NAV_ITEMS.length);
    expect(new Set(NAV_ITEMS.map((item) => item.path)).size).toBe(NAV_ITEMS.length);
  });
});

describe('constants/nav — resolveModuleTitle 顶栏标题解析', () => {
  it('有导航入口的路径取导航文案', () => {
    expect(resolveModuleTitle('/')).toBe('概览');
    expect(resolveModuleTitle('/docs/123')).toBe('需求文档');
    expect(resolveModuleTitle('/tasks/abc')).toBe('生成任务');
    expect(resolveModuleTitle('/testpoints')).toBe('测试点评审');
  });

  it('/testpoints/:docId 按前缀命中导航项，不落到「控制台」', () => {
    // T12-1b W2 回归点：这一页曾经因为侧栏没有入口、只靠 matchNavItem 查不到而
    // 显示成「控制台」。T12-2a 给测试点评审补了导航项后，前缀规则直接命中，
    // 当初为它加的 EXTRA_TITLES 正则因此成了死代码、已删除；这条断言守住行为不变。
    expect(resolveModuleTitle('/testpoints/9')).toBe('测试点评审');
    expect(resolveModuleTitle('/testpoints/1234567890')).toBe('测试点评审');
    // 多级后缀也走前缀命中
    expect(resolveModuleTitle('/testpoints/9/extra')).toBe('测试点评审');
  });

  it('未知路径回退「控制台」，且返回值永不为空串', () => {
    expect(resolveModuleTitle('/unknown')).toBe(FALLBACK_MODULE_TITLE);
    expect(resolveModuleTitle('')).toBe(FALLBACK_MODULE_TITLE);
    expect(resolveModuleTitle('/unknown')).not.toBe('');
  });
});
