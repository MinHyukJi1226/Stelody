import { z } from 'zod'
import { ApiError, ApiResponseError } from './errors'

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
}

const csrfSchema = z.object({
  headerName: z.literal('X-CSRF-TOKEN'),
  token: z.string().min(1),
})
type CsrfToken = z.infer<typeof csrfSchema>

export function createApiClient(baseUrl: string) {
  // Only an explicit API base can select the destination; request paths cannot.
  const base = baseUrl.replace(/\/+$/, '')
  if (!/^\/(?!\/)/.test(base) && !/^https?:\/\//.test(base)) {
    throw new Error(
      'API 주소는 /로 시작하는 경로 또는 HTTP(S) 주소여야 합니다.',
    )
  }
  let csrf: Promise<CsrfToken> | undefined

  function invalidateCsrf() {
    csrf = undefined
  }

  function url(path: string) {
    if (
      !/^\/(?!\/)/.test(path) ||
      /[\\#]/.test(path) ||
      path
        .split('?')[0]
        ?.split('/')
        .some((p) => p === '..' || p === '.')
    ) {
      throw new Error('API 요청 경로가 올바르지 않습니다.')
    }
    return `${base}${path}`
  }

  async function send(
    path: string,
    options: RequestOptions,
    token?: CsrfToken,
  ) {
    const destination = url(path)
    const headers = new Headers({ Accept: 'application/json' })
    if (options.body !== undefined)
      headers.set('Content-Type', 'application/json')
    if (token) headers.set(token.headerName, token.token)
    const response = await fetch(destination, {
      method: options.method ?? 'GET',
      credentials: 'include',
      redirect: 'error',
      headers,
      ...(options.body !== undefined
        ? { body: JSON.stringify(options.body) }
        : {}),
      ...(options.signal ? { signal: options.signal } : {}),
    })
    if (response.status === 204) return undefined
    const contentType = response.headers.get('content-type') ?? ''
    const isJson = /^application\/(?:json|[\w.-]+\+json)(?:;|$)/i.test(
      contentType,
    )
    let payload: unknown
    if (isJson) {
      try {
        payload = await response.json()
      } catch {
        if (response.ok) throw new ApiResponseError()
      }
    }
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) invalidateCsrf()
      throw new ApiError(response.status, payload)
    }
    if (!isJson) throw new ApiResponseError()
    return payload
  }

  function getCsrf(): Promise<CsrfToken> {
    if (!csrf) {
      const pending = send('/auth/csrf', {}).then((data) => {
        const result = csrfSchema.safeParse(data)
        if (!result.success) throw new ApiResponseError()
        return result.data
      })
      csrf = pending
      void pending.catch(() => {
        if (csrf === pending) csrf = undefined
      })
    }
    return csrf
  }

  async function request<T>(
    path: string,
    options: RequestOptions = {},
  ): Promise<T> {
    options.signal?.throwIfAborted()
    const method = options.method ?? 'GET'
    const token = method === 'GET' ? undefined : await getCsrf()
    options.signal?.throwIfAborted()
    try {
      return (await send(path, options, token)) as T
    } finally {
      // Authentication transitions may rotate the session even if delivery failed.
      if (method !== 'GET' && path.split('?')[0] === '/auth/logout')
        invalidateCsrf()
    }
  }

  return { request, invalidateCsrf, url }
}

export const apiClient = createApiClient(
  import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
)
