const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
const apiBaseUrl = (configuredBaseUrl || '/api/v1/space').replace(/\/$/, '')

export class ApiRequestError extends Error {
  public readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/**
 * 向空间目标服务发起 GET 请求，并将 JSON 响应转换为调用方声明的类型。
 */
export async function getApi<T>(path: string): Promise<T> {
  return requestApi<T>(path, { method: 'GET' })
}

/**
 * 向空间目标服务发起 JSON POST 请求，统一设置内容类型并序列化请求体。
 */
export async function postApi<T>(path: string, payload: unknown): Promise<T> {
  return requestApi<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

/**
 * 向空间目标服务发起 JSON PUT 请求，用于更新卫星等长期基础配置。
 *
 * @param path 相对于空间目标服务根路径的接口地址
 * @param payload 需要序列化的请求体
 * @returns 后端返回的业务响应
 */
export async function putApi<T>(path: string, payload: unknown): Promise<T> {
  return requestApi<T>(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

/**
 * 向空间目标服务发起 DELETE 请求，用于取消异步规划任务等可撤销操作。
 *
 * @param path 相对于空间目标服务根路径的接口地址
 * @returns 后端返回的业务响应
 */
export async function deleteApi<T>(path: string): Promise<T> {
  return requestApi<T>(path, { method: 'DELETE' })
}

/**
 * 执行实际 HTTP 调用；非成功响应转换为包含状态码和业务错误信息的 ApiRequestError。
 */
async function requestApi<T>(path: string, options: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, options)
  const responseText = await response.text()
  const body = responseText ? parseResponse(responseText) : null

  if (!response.ok) {
    const message = isErrorResponse(body) ? body.error : `请求失败（HTTP ${response.status}）`
    throw new ApiRequestError(response.status, message)
  }

  return body as T
}

/**
 * 优先按 JSON 解析响应正文；非 JSON 文本原样返回，便于显示网关或服务异常内容。
 */
function parseResponse(responseText: string): unknown {
  try {
    return JSON.parse(responseText)
  } catch {
    return responseText
  }
}

/**
 * 判断响应对象是否符合后端统一异常结构，避免不安全的属性访问。
 */
function isErrorResponse(value: unknown): value is { error: string } {
  return typeof value === 'object' && value !== null && 'error' in value && typeof value.error === 'string'
}
