import { get, post } from '@/api/http';
import type { CodeLabel, LoginResponse, PublicConfig, UserVO } from '@/types/common';

/**
 * 登录（白名单接口，无需 token）。
 *
 * @param payload 用户名与明文密码
 * @returns token 与用户信息
 */
export function login(payload: { username: string; password: string }): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/login', payload);
}

/**
 * 当前登录用户（用于校验 token 是否仍有效）。
 *
 * @returns 用户信息
 */
export function fetchCurrentUser(): Promise<UserVO> {
  return get<UserVO>('/auth/me');
}

/**
 * 公开配置：枚举字典 + 应用信息 + 限制值（白名单接口）。
 *
 * @returns 公开配置
 */
export function fetchPublicConfig(): Promise<PublicConfig> {
  return get<PublicConfig>('/config/public');
}

/**
 * 通用字典查询（白名单接口）。
 *
 * @param type 字典类型，如 `case-types`
 * @param enabledOnly 是否只取启用项
 * @returns 字典项
 */
export function fetchDict(type: string, enabledOnly = true): Promise<CodeLabel[]> {
  return get<CodeLabel[]>(`/dict/${type}`, { enabledOnly });
}
