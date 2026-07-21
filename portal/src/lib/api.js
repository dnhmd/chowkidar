const BASE_URL = '/api'

export async function validateApiKey(apiKey) {
    const res = await fetch(`${BASE_URL}/management/auth/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apiKey })
    })
    if (!res.ok) throw new Error('Invalid API key')
    return res.json()
}

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

export async function deleteTenant(id) {
    const res = await fetch(`${BASE_URL}/management/tenants/${id}`, {
        method: 'DELETE'
    })
    if (!res.ok) throw new Error('Failed to delete tenant')
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