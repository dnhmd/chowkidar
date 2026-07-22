const BASE_URL = '/api'

export async function getTenants() {
    const res = await fetch(`${BASE_URL}/management/tenants`)
    if (!res.ok) throw new Error('Failed to fetch tenants')
    return res.json()
}

export async function createTenant(name) {
    const res = await fetch(`${BASE_URL}/management/tenants`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
    })
    if (!res.ok) throw new Error('Failed to create tenant')
    return res.json()
}

export async function updateTenant(id, name) {
    const res = await fetch(`${BASE_URL}/management/tenants/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
    })
    if (!res.ok) throw new Error('Failed to update tenant')
    return res.json()
}

export async function deleteTenant(id) {
    const res = await fetch(`${BASE_URL}/management/tenants/${id}`, {
        method: 'DELETE'
    })
    if (!res.ok) throw new Error('Failed to delete tenant')
}

export async function validateApiKey(apiKey) {
    const res = await fetch(`${BASE_URL}/management/auth/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apiKey })
    })
    if (!res.ok) throw new Error('Invalid API key')
    return res.json()
}

export async function getRoutes(tenantId, apiKey) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes`, {
        headers: { 'X-API-Key': apiKey }
    })
    if (!res.ok) throw new Error('Failed to fetch routes')
    return res.json()
}

export async function createRoute(tenantId, apiKey, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to create route')
    return res.json()
}

export async function deleteRoute(tenantId, apiKey, routeId) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes/${routeId}`, {
        method: 'DELETE',
        headers: { 'X-API-Key': apiKey }
    })
    if (!res.ok) throw new Error('Failed to delete route')
}

export async function updateRouteUrl(tenantId, apiKey, routeId, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes/${routeId}/upstream`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to update route URL')
    return res.json()
}

export async function updateRouteRate(tenantId, apiKey, routeId, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes/${routeId}/rate`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to update route rate')
    return res.json()
}

export async function updateRouteTimeout(tenantId, apiKey, routeId, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes/${routeId}/timeout`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to update route timeout')
    return res.json()
}

export async function updateRouteIdempotency(tenantId, apiKey, routeId, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes/${routeId}/idempotency`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to update route idempotency')
    return res.json()
}

export async function getRouteHealth(tenantId, apiKey) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/routes/health`, {
        headers: { 'X-API-Key': apiKey }
    })
    if (!res.ok) throw new Error('Failed to fetch route health')
    return res.json()
}

export async function getIpRules(tenantId, apiKey) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/ip-rules`, {
        headers: { 'X-API-Key': apiKey }
    })
    if (!res.ok) throw new Error('Failed to fetch IP rules')
    return res.json()
}

export async function createIpRule(tenantId, apiKey, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/ip-rules`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to create IP rule')
    return res.json()
}

export async function updateIpRule(tenantId, apiKey, ruleId, data) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/ip-rules/${ruleId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify(data)
    })
    if (!res.ok) throw new Error('Failed to update IP rule')
    return res.json()
}

export async function deleteIpRule(tenantId, apiKey, ruleId) {
    const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/ip-rules/${ruleId}`, {
        method: 'DELETE',
        headers: { 'X-API-Key': apiKey }
    })
    if (!res.ok) throw new Error('Failed to delete IP rule')
}

export async function rotateApiKey(tenantId, apiKey) {
  const res = await fetch(`${BASE_URL}/management/tenants/${tenantId}/rotate-key`, {
    method: 'POST',
    headers: { 'X-API-Key': apiKey }
  })
  if (!res.ok) throw new Error('Failed to rotate API key')
  return res.json()
}