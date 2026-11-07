/// <reference types="vite/client" />

/**
 * Vite 环境变量类型声明。
 *
 * 约定：前端所有后端地址统一走 {@code VITE_API_BASE}，缺省 `/api`（走 vite dev proxy）。
 */
interface ImportMetaEnv {
  /** 后端 API 基地址，缺省 `/api` */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
