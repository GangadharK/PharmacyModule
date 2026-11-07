import { useAuthStore } from '@/store/auth.store'
import type { ApiError, Uuid } from '@/types/api'

/**
 * The single path to the network.
 *
 * SCMS has two competing fetch styles - some components call raw `fetch` with a
 * token scraped from sessionStorage, bypassing the client. That means a 401
 * does not log anyone out, and an error is shaped differently depending on
 * which component made the call. Not copied. See code-standards.md 3.2.
 *
 * baseUrl is the relative '/api/v1'. The Vite dev proxy forwards /api to the
 * backend, so the browser sees a same-origin request: no CORS pre-flight, and
 * the HttpOnly refresh cookie is sent normally.
 */

const BASE_URL = '/api/v1'

export class ApiRequestError extends Error {
  readonly code: string
  readonly status: number
  readonly fieldErrors: Record<string, string> | undefined
  readonly apiError: ApiError | null

  constructor(
    message: string,
    code: string,
    status: number,
    apiError: ApiError | null = null,
  ) {
    super(message)
    this.name = 'ApiRequestError'
    this.code = code
    this.status = status
    this.apiError = apiError
    this.fieldErrors = apiError?.fieldErrors
  }
}

export class UnauthorizedError extends ApiRequestError {
  constructor() {
    super('Your session has expired. Please sign in again.', 'UNAUTHENTICATED', 401)
    this.name = 'UnauthorizedError'
  }
}

export class NetworkError extends ApiRequestError {
  constructor(cause: unknown) {
    super(
      'Could not reach the server. Check that the backend is running.',
      'NETWORK_ERROR',
      0,
    )
    this.name = 'NetworkError'
    this.cause = cause
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  query?: Record<string, string | number | boolean | undefined | null>
  /** Skip the Authorization header. Used by the login call. */
  anonymous?: boolean
  signal?: AbortSignal
}

class ApiClient {
  private readonly baseUrl = BASE_URL

  /**
   * @throws {UnauthorizedError} on 401, after clearing the auth store
   * @throws {ApiRequestError} on any other non-2xx
   * @throws {NetworkError} when the request does not complete
   */
  private async request<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
    const { method = 'GET', body, query, anonymous = false, signal } = options

    const url = new URL(`${this.baseUrl}${endpoint}`, window.location.origin)
    if (query) {
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== null) {
          url.searchParams.set(key, String(value))
        }
      }
    }

    // Read the token at call time, never capture it at construction. A client
    // that cached the token on login would send a stale one after any refresh.
    const token = anonymous ? null : useAuthStore.getState().accessToken

    const headers: Record<string, string> = {}
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    let response: Response
    try {
      response = await fetch(url.toString(), {
        method,
        headers,
        signal,
        credentials: 'include',
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch (cause) {
      // A fetch rejection is a transport failure - the request never reached
      // the server, or the connection dropped mid-flight.
      throw new NetworkError(cause)
    }

    if (response.status === 401) {
      // Central logout. Any component can call any endpoint and a 401 always
      // clears the session, because this is the only place fetch is called.
      useAuthStore.getState().logout()
      throw new UnauthorizedError()
    }

    if (response.status === 204) {
      return undefined as T
    }

    if (!response.ok) {
      throw await this.toError(response)
    }

    return (await response.json()) as T
  }

  /**
   * Parse an error response into an ApiRequestError.
   *
   * Falls back to a synthetic envelope when the body is not the expected JSON.
   * A reverse proxy or gateway in front of the API can return an HTML error
   * page; without this fallback the JSON parse throws and the user sees
   * "Unexpected token <" instead of a usable message.
   */
  private async toError(response: Response): Promise<ApiRequestError> {
    let envelope: ApiError | null = null
    try {
      const body: unknown = await response.json()
      if (
        typeof body === 'object' &&
        body !== null &&
        'code' in body &&
        'status' in body
      ) {
        envelope = body as ApiError
      }
    } catch {
      envelope = null
    }

    if (envelope) {
      return new ApiRequestError(envelope.message, envelope.code, response.status, envelope)
    }

    return new ApiRequestError(
      `Request failed with status ${response.status}`,
      'UNKNOWN',
      response.status,
    )
  }

  // ==================== Verbs ====================

  get<T>(endpoint: string, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: 'GET' })
  }

  post<T>(endpoint: string, body?: unknown, options: Omit<RequestOptions, 'method'> = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: 'POST', body })
  }

  put<T>(endpoint: string, body?: unknown, options: Omit<RequestOptions, 'method'> = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: 'PUT', body })
  }

  patch<T>(endpoint: string, body?: unknown, options: Omit<RequestOptions, 'method'> = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: 'PATCH', body })
  }

  delete<T>(endpoint: string, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: 'DELETE' })
  }

  /** Absolute path, for endpoints outside /api/v1. Currently none. */
  getAbsolute<T>(path: string): Promise<T> {
    return this.request<T>(path)
  }
}

export const apiClient = new ApiClient()

/** Narrow an unknown catch value to an ApiRequestError without a cast. */
export function isApiRequestError(error: unknown): error is ApiRequestError {
  return error instanceof ApiRequestError
}

export function toUuid(value: string): Uuid {
  return value as Uuid
}
