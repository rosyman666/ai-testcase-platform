/**
 * T01 契约地基测试：blob 判别的两条必过路径（docs/07 §8 T01 完成判据 ④）。
 *
 * ① 40003 的 JSON blob → **不下载**、toast 出现、抛 `ApiError.code === 40003`；
 * ② 二进制 blob → 按 Content-Disposition 取文件名，`createObjectURL` /
 *    `revokeObjectURL` 成对出现且只各一次。
 *
 * 这两条要挡住的具体事故是 D-46 同型的「测错对象」：把错误 JSON 当 xlsx 下载后，
 * 用户得到一个空文件、界面没有任何提示，而「不产生可下载文件」这条验收**永远不会红**。
 */

import type { AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/utils/toast', () => ({ toast: vi.fn() }));

import { ApiError } from '@/api/http';
import { toast } from '@/utils/toast';
import {
  buildFallbackFilename,
  downloadOrThrow,
  parseContentDispositionFilename,
} from '@/utils/download';

/** 造一个「完整 AxiosResponse」，只保留 downloadOrThrow 需要的字段。 */
function fakeResponse(
  blob: Blob,
  headers: Record<string, string> = {},
): AxiosResponse<Blob> {
  return {
    data: blob,
    headers,
    status: 200,
    statusText: 'OK',
    config: { responseType: 'blob' },
  } as unknown as AxiosResponse<Blob>;
}

const toastMock = vi.mocked(toast);

let createObjectURLSpy: ReturnType<typeof vi.fn>;
let revokeObjectURLSpy: ReturnType<typeof vi.fn>;
let clickSpy: ReturnType<typeof vi.spyOn>;
let originalCreate: typeof URL.createObjectURL | undefined;
let originalRevoke: typeof URL.revokeObjectURL | undefined;
let downloadedName = '';

beforeEach(() => {
  toastMock.mockClear();
  downloadedName = '';

  originalCreate = URL.createObjectURL;
  originalRevoke = URL.revokeObjectURL;
  createObjectURLSpy = vi.fn(() => 'blob:mock-url');
  revokeObjectURLSpy = vi.fn();
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: createObjectURLSpy,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: revokeObjectURLSpy,
  });

  clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    downloadedName = this.download;
  });
});

afterEach(() => {
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: originalCreate,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: originalRevoke,
  });
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('downloadOrThrow — 错误 JSON blob 不下载', () => {
  it('40003（没有符合条件的用例）→ 无下载 + toast + ApiError.code===40003', async () => {
    const envelope = {
      code: 40003,
      message: '没有符合条件的用例',
      data: null,
      traceId: 'trace-empty',
    };
    const blob = new Blob([JSON.stringify(envelope)], { type: 'application/json' });
    const response = fakeResponse(blob, {
      'content-type': 'application/json;charset=UTF-8',
      'content-disposition': '',
    });

    await expect(downloadOrThrow(response, { fallbackFilename: 'fallback.xlsx' }))
      .rejects.toBeInstanceOf(ApiError);

    await expect(downloadOrThrow(response, { fallbackFilename: 'fallback.xlsx' }))
      .rejects.toMatchObject({ code: 40003, message: '没有符合条件的用例', traceId: 'trace-empty' });

    expect(toastMock).toHaveBeenCalledWith('没有符合条件的用例', 'error');
    expect(createObjectURLSpy).not.toHaveBeenCalled();
    expect(revokeObjectURLSpy).not.toHaveBeenCalled();
    expect(clickSpy).not.toHaveBeenCalled();
    expect(downloadedName).toBe('');
  });

  it('只有响应头带 application/json 也能判别（Blob.type 为空时不漏判）', async () => {
    const blob = new Blob(
      [JSON.stringify({ code: 40001, message: 'statuses 含非法值', data: null, traceId: '' })],
      { type: '' },
    );
    const response = fakeResponse(blob, { 'Content-Type': 'application/json;charset=UTF-8' });

    await expect(downloadOrThrow(response)).rejects.toMatchObject({ code: 40001 });
    expect(createObjectURLSpy).not.toHaveBeenCalled();
  });

  it('导出端点误返回「成功 JSON」→ 抛 ApiError 且同样不下载', async () => {
    const blob = new Blob(
      [JSON.stringify({ code: 0, message: 'ok', data: null, traceId: '' })],
      { type: 'application/json' },
    );
    const response = fakeResponse(blob, { 'content-type': 'application/json' });

    await expect(downloadOrThrow(response)).rejects.toMatchObject({ code: 50000 });
    expect(clickSpy).not.toHaveBeenCalled();
  });
});

describe('downloadOrThrow — 二进制 blob 触发下载', () => {
  it('按 Content-Disposition 取文件名，createObjectURL/revokeObjectURL 成对', async () => {
    const blob = new Blob(['PK\u0003\u0004binary'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const response = fakeResponse(blob, {
      'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'content-disposition': 'attachment; filename="T20261112_20261112-103000.xlsx"',
    });

    const result = await downloadOrThrow(response, { fallbackFilename: 'fallback.xlsx' });

    expect(result.filename).toBe('T20261112_20261112-103000.xlsx');
    expect(downloadedName).toBe('T20261112_20261112-103000.xlsx');
    expect(createObjectURLSpy).toHaveBeenCalledTimes(1);
    expect(createObjectURLSpy).toHaveBeenCalledWith(blob);
    expect(revokeObjectURLSpy).toHaveBeenCalledTimes(1);
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url');
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(toastMock).not.toHaveBeenCalled();
  });

  it('filename*=UTF-8 优先于 filename=，中文名不乱码', async () => {
    const encoded = encodeURIComponent('用例导出_中文.xlsx');
    const blob = new Blob(['binary'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const response = fakeResponse(blob, {
      'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'content-disposition': `attachment; filename="fallback.xlsx"; filename*=UTF-8''${encoded}`,
    });

    const result = await downloadOrThrow(response, { fallbackFilename: 'fallback.xlsx' });

    expect(result.filename).toBe('用例导出_中文.xlsx');
    expect(createObjectURLSpy).toHaveBeenCalledTimes(1);
    expect(revokeObjectURLSpy).toHaveBeenCalledTimes(1);
  });

  it('缺少 Content-Disposition 时用兜底名', async () => {
    const blob = new Blob(['binary'], { type: 'application/zip' });
    const response = fakeResponse(blob, { 'content-type': 'application/zip' });
    const fallback = buildFallbackFilename('8899', 'zip', new Date(2026, 10, 12, 10, 30));

    const result = await downloadOrThrow(response, { fallbackFilename: fallback });

    expect(fallback).toBe('T8899_20261112-1030.zip');
    expect(result.filename).toBe(fallback);
    expect(downloadedName).toBe(fallback);
  });
});

describe('parseContentDispositionFilename — 三种写法与兜底', () => {
  it('filename="..." 与无引号形式', () => {
    expect(parseContentDispositionFilename('attachment; filename="a.xlsx"', 'f.xlsx')).toBe('a.xlsx');
    expect(parseContentDispositionFilename('attachment; filename=a.xlsx', 'f.xlsx')).toBe('a.xlsx');
  });

  it('非法百分号编码时回落 filename=，不抛异常', () => {
    const disposition = "attachment; filename*=UTF-8''%E0%A4%A; filename=\"b.zip\"";
    expect(parseContentDispositionFilename(disposition, 'f.zip')).toBe('b.zip');
  });

  it('完全缺失时用兜底', () => {
    expect(parseContentDispositionFilename(undefined, 'f.zip')).toBe('f.zip');
    expect(parseContentDispositionFilename('attachment', 'f.zip')).toBe('f.zip');
  });
});
