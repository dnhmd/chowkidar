<script>
  import { onMount } from 'svelte'
  import { push } from 'svelte-spa-router'
  import { getTenants, createTenant, deleteTenant, updateTenant, validateApiKey } from '../lib/api.js'
  import { saveSession, getSession } from '../lib/session.js'
  import Modal from '../components/Modal.svelte'
  import TenantCard from '../components/TenantCard.svelte'

  let tenants = []
  let loading = true
  let error = null

  // Modal state
  let showCreateModal = false
  let showLoginModal = false
  let showEditModal = false
  let showApiKeyModal = false

  // Form state
  let newTenantName = ''
  let editTenantName = ''
  let loginApiKey = ''
  let createdApiKey = ''
  let selectedTenant = null

  let creating = false
  let loggingIn = false
  let formError = null

  onMount(async () => {
    const session = getSession()
    if (session) {
      push('/tenant')
      return
    }
    await loadTenants()
  })

  async function loadTenants() {
    loading = true
    error = null
    try {
      tenants = await getTenants()
    } catch (e) {
      error = 'Failed to load tenants.'
    } finally {
      loading = false
    }
  }

  async function handleCreate() {
    if (!newTenantName.trim()) return
    creating = true
    formError = null
    try {
      const response = await createTenant(newTenantName.trim())
      createdApiKey = response.apiKey
      showCreateModal = false
      showApiKeyModal = true
      newTenantName = ''
      await loadTenants()
    } catch (e) {
      formError = 'Failed to create tenant.'
    } finally {
      creating = false
    }
  }

  async function handleEdit() {
    if (!editTenantName.trim()) return
    formError = null
    try {
      await updateTenant(selectedTenant.id, editTenantName.trim())
      showEditModal = false
      editTenantName = ''
      selectedTenant = null
      await loadTenants()
    } catch (e) {
      formError = 'Failed to update tenant.'
    }
  }

  async function handleDelete(tenant) {
    if (!confirm(`Delete tenant "${tenant.name}"? This cannot be undone.`)) return
    try {
      await deleteTenant(tenant.id)
      await loadTenants()
    } catch (e) {
      error = 'Failed to delete tenant.'
    }
  }

  async function handleLogin() {
    if (!loginApiKey.trim()) return
    loggingIn = true
    formError = null
    try {
      const response = await validateApiKey(loginApiKey.trim())
      saveSession(response.tenantId, response.tenantName, loginApiKey.trim(), response.status, response.isDeprecated)
      push('/tenant')
    } catch (e) {
      formError = 'Invalid API key.'
    } finally {
      loggingIn = false
    }
  }

  function openLogin(tenant) {
    selectedTenant = tenant
    loginApiKey = ''
    formError = null
    showLoginModal = true
  }

  function openEdit(tenant) {
    selectedTenant = tenant
    editTenantName = tenant.name
    formError = null
    showEditModal = true
  }
</script>

<div class="home">
  <header>
    <div class="brand">
      <h1>Chowkidar</h1>
      <p>API Gateway Management</p>
    </div>
    <button class="btn-primary" on:click={() => { showCreateModal = true; formError = null }}>
      + New Tenant
    </button>
  </header>

  <main>
    {#if loading}
      <div class="state-message">Loading tenants...</div>
    {:else if error}
      <div class="state-message error">{error}</div>
    {:else if tenants.length === 0}
      <div class="empty-state">
        <h2>No tenants yet</h2>
        <p>Create your first tenant to get started.</p>
        <button class="btn-primary" on:click={() => showCreateModal = true}>+ New Tenant</button>
      </div>
    {:else}
      <div class="tenant-list">
        {#each tenants as tenant (tenant.id)}
          <TenantCard
            {tenant}
            onLogin={openLogin}
            onDelete={handleDelete}
            onEdit={openEdit}
          />
        {/each}
      </div>
    {/if}
  </main>
</div>

{#if showCreateModal}
  <Modal title="New Tenant" onClose={() => showCreateModal = false}>
    <input
      type="text"
      placeholder="Tenant name"
      bind:value={newTenantName}
      on:keydown={(e) => e.key === 'Enter' && handleCreate()}
    />
    {#if formError}<p class="form-error">{formError}</p>{/if}
    <button class="btn-primary" on:click={handleCreate} disabled={creating}>
      {creating ? 'Creating...' : 'Create Tenant'}
    </button>
  </Modal>
{/if}

{#if showApiKeyModal}
  <Modal title="Tenant Created" onClose={() => showApiKeyModal = false}>
    <p>Copy your API key now. It will not be shown again.</p>
    <div class="api-key-box">{createdApiKey}</div>
    <button class="btn-primary" on:click={() => navigator.clipboard.writeText(createdApiKey)}>
      Copy to Clipboard
    </button>
    <button class="btn-ghost" on:click={() => showApiKeyModal = false}>Done</button>
  </Modal>
{/if}

{#if showLoginModal}
  <Modal title="Login as {selectedTenant?.name}" onClose={() => showLoginModal = false}>
    <p>Enter the API key for this tenant.</p>
    <input
      type="text"
      placeholder="API Key"
      bind:value={loginApiKey}
      on:keydown={(e) => e.key === 'Enter' && handleLogin()}
    />
    {#if formError}<p class="form-error">{formError}</p>{/if}
    <button class="btn-primary" on:click={handleLogin} disabled={loggingIn}>
      {loggingIn ? 'Validating...' : 'Login'}
    </button>
  </Modal>
{/if}

{#if showEditModal}
  <Modal title="Edit Tenant" onClose={() => showEditModal = false}>
    <input
      type="text"
      placeholder="Tenant name"
      bind:value={editTenantName}
      on:keydown={(e) => e.key === 'Enter' && handleEdit()}
    />
    {#if formError}<p class="form-error">{formError}</p>{/if}
    <button class="btn-primary" on:click={handleEdit}>Save Changes</button>
  </Modal>
{/if}

<style>
  .home {
    min-height: 100vh;
    padding: var(--space-4);
    max-width: 800px;
    margin: 0 auto;
  }

  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--space-5);
    padding-bottom: var(--space-3);
    border-bottom: 1px solid var(--border);
  }

  .brand h1 {
    font-size: 1.75rem;
    color: var(--text-primary);
  }

  .brand p {
    font-size: 0.875rem;
    color: var(--text-secondary);
  }

  .btn-primary {
    background: var(--accent);
    color: white;
    padding: 10px 20px;
    border-radius: var(--radius-sm);
    font-size: 0.875rem;
    font-weight: 500;
    transition: opacity 0.2s;
  }

  .btn-primary:hover {
    opacity: 0.85;
  }

  .btn-primary:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .btn-ghost {
    background: transparent;
    color: var(--text-secondary);
    border: 1px solid var(--border);
    padding: 10px 20px;
    border-radius: var(--radius-sm);
    font-size: 0.875rem;
  }

  .tenant-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .empty-state {
    text-align: center;
    padding: var(--space-6);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-2);
  }

  .state-message {
    text-align: center;
    padding: var(--space-5);
    color: var(--text-secondary);
  }

  .state-message.error {
    color: #c0392b;
  }

  input {
    width: 100%;
    padding: 10px var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
    color: var(--text-primary);
    transition: border-color 0.2s;
  }

  input:focus {
    border-color: var(--accent);
  }

  .api-key-box {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: var(--space-2);
    font-family: var(--font-mono);
    font-size: 0.85rem;
    word-break: break-all;
    color: var(--text-primary);
  }

  .form-error {
    color: #c0392b;
    font-size: 0.875rem;
  }
</style>