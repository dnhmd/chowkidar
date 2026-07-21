const SESSION_KEY = 'chowkidar_tenant_session'
const SESSION_TTL_MS = 8 * 60 * 60 * 1000 // 8 hours

export function saveSession(tenantId, tenantName, apiKey, status, isDeprecated) {
  const session = {
    tenantId,
    tenantName,
    apiKey,
    status,
    isDeprecated,
    expiresAt: Date.now() + SESSION_TTL_MS
  }
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function getSession() {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  const session = JSON.parse(raw)
  if (Date.now() > session.expiresAt) {
    clearSession()
    return null
  }
  return session
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY)
}