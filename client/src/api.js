const TOKEN_KEY = 'videorag_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export async function api(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let body = options.body
  if (body && !(body instanceof FormData) && typeof body !== 'string') {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(body)
  }

  const response = await fetch(path, { ...options, headers, body })
  if (response.status === 401) {
    clearToken()
    if (!path.startsWith('/user/')) {
      window.location.href = '/login'
    }
    throw new Error('登录状态已失效')
  }

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    const text = await response.text()
    if (!response.ok) throw new Error(text || `请求失败 (${response.status})`)
    return text
  }

  const payload = await response.json()
  if (payload && typeof payload.code === 'number') {
    if (payload.code !== 0) throw new Error(payload.message || '请求失败')
    return payload.data
  }
  if (!response.ok) throw new Error(`请求失败 (${response.status})`)
  return payload
}

/**
 * POST + SSE。后端事件：start / sources / delta / done / error。
 * 返回 AbortController，调用 abort() 可停止生成。
 */
export function streamSSE(path, body, handlers) {
  const controller = new AbortController()
  const token = getToken()

  fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body),
    signal: controller.signal
  }).then(response => consumeStream(response, handlers))
    .catch(error => {
      if (error.name !== 'AbortError') handlers.error?.({ message: error.message })
    })

  return controller
}

export function streamSSEGet(path, handlers) {
  const controller = new AbortController()
  const token = getToken()
  fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: controller.signal
  }).then(response => consumeStream(response, handlers))
    .catch(error => {
      if (error.name !== 'AbortError') handlers.error?.({ message: error.message })
    })
  return controller
}

async function consumeStream(response, handlers) {
  if (!response.ok) {
    let message = `流式请求失败 (${response.status})`
    try {
      const payload = await response.json()
      if (payload?.message) message = payload.message
    } catch {
      // 非 JSON 错误体，保留通用文案
    }
    throw new Error(message)
  }
  if (!response.body) {
    throw new Error('流式响应为空')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      dispatchEvent(raw, handlers)
    }
  }
}

function dispatchEvent(raw, handlers) {
  let event = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return
  const text = dataLines.join('\n')
  let payload = text
  try {
    payload = JSON.parse(text)
  } catch {
    // 保持原始文本
  }
  handlers[event]?.(payload)
}
