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

  it('不在导航表里的 /testpoints/:docId 不命中任何项（顶栏退回「控制台」）', () => {
    // 测试点评审从文档详情页跳进去，导航里没有独立入口，所以这里应当无命中
    expect(matchNavItem('/testpoints/1')).toBeUndefined();
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
  });

  it('有页面但侧栏没有入口的 /testpoints/:docId 不能被显示成「控制台」', () => {
    // T12-1b W2 回归点：测试点评审从文档详情页跳进去，侧栏没有它的导航项，
    // 只靠 matchNavItem 会回退成「控制台」，属于误导性文案
    expect(resolveModuleTitle('/testpoints/9')).toBe('测试点评审');
    expect(resolveModuleTitle('/testpoints/1234567890')).toBe('测试点评审');
  });

  it('额外标题表按动态段正则匹配，/testpoints 本身（无 docId）仍回退默认标题', () => {
    expect(resolveModuleTitle('/testpoints')).toBe(FALLBACK_MODULE_TITLE);
    expect(resolveModuleTitle('/testpoints/9/extra')).toBe(FALLBACK_MODULE_TITLE);
  });

  it('未知路径回退「控制台」，且返回值永不为空串', () => {
    expect(resolveModuleTitle('/unknown')).toBe(FALLBACK_MODULE_TITLE);
    expect(resolveModuleTitle('')).toBe(FALLBACK_MODULE_TITLE);
    expect(resolveModuleTitle('/unknown')).not.toBe('');
  });
});
