<script>
  import { onMount } from "svelte";
  import { push } from "svelte-spa-router";
  import {
    getTenants,
    createTenant,
    deleteTenant,
    updateTenant,
    validateApiKey,
  } from "../lib/api.js";
  import { saveSession, getSession } from "../lib/session.js";
  import Modal from "../components/Modal.svelte";
  import TenantCard from "../components/home/TenantCard.svelte";

  let tenants = [];
  let loading = true;
  let error = null;

  // Modal state
  let showCreateModal = false;
  let showLoginModal = false;
  let showEditModal = false;
  let showApiKeyModal = false;

  // Form state
  let newTenantName = "";
  let editTenantName = "";
  let loginApiKey = "";
  let createdApiKey = "";
  let selectedTenant = null;

  let creating = false;
  let loggingIn = false;
  let formError = null;

  onMount(async () => {
    const session = getSession();
    if (session) {
      push("/tenant");
      return;
    }
    await loadTenants();
  });

  async function loadTenants() {
    loading = true;
    error = null;
    try {
      tenants = await getTenants();
    } catch (e) {
      error = "Failed to load tenants.";
    } finally {
      loading = false;
    }
  }

  async function handleCreate() {
    if (!newTenantName.trim()) return;
    creating = true;
    formError = null;
    try {
      const response = await createTenant(newTenantName.trim());
      createdApiKey = response.apiKey;
      showCreateModal = false;
      showApiKeyModal = true;
      newTenantName = "";
      await loadTenants();
    } catch (e) {
      formError = "Failed to create tenant.";
    } finally {
      creating = false;
    }
  }

  async function handleEdit() {
    if (!editTenantName.trim()) return;
    formError = null;
    try {
      await updateTenant(selectedTenant.id, editTenantName.trim());
      showEditModal = false;
      editTenantName = "";
      selectedTenant = null;
      await loadTenants();
    } catch (e) {
      formError = "Failed to update tenant.";
    }
  }

  async function handleDelete(tenant) {
    if (!confirm(`Delete tenant "${tenant.name}"? This cannot be undone.`))
      return;
    try {
      await deleteTenant(tenant.id);
      await loadTenants();
    } catch (e) {
      error = "Failed to delete tenant.";
    }
  }

  async function handleLogin() {
    if (!loginApiKey.trim()) return;
    loggingIn = true;
    formError = null;
    try {
      const response = await validateApiKey(loginApiKey.trim());
      saveSession(
        response.tenantId,
        response.tenantName,
        loginApiKey.trim(),
        response.status,
        response.isDeprecated,
      );
      push("/tenant");
    } catch (e) {
      formError = "Invalid API key.";
    } finally {
      loggingIn = false;
    }
  }

  function openLogin(tenant) {
    selectedTenant = tenant;
    loginApiKey = "";
    formError = null;
    showLoginModal = true;
  }

  function openEdit(tenant) {
    selectedTenant = tenant;
    editTenantName = tenant.name;
    formError = null;
    showEditModal = true;
  }
</script>

<div class="home">
  <header>
    <div class="brand">
      <h1>Chowkidar</h1>
      <p>API Gateway Management</p>
    </div>
    <button
      class="btn-primary"
      on:click={() => {
        showCreateModal = true;
        formError = null;
      }}
    >
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
        <button class="btn-primary" on:click={() => (showCreateModal = true)}
          >+ New Tenant</button
        >
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
  <Modal title="New Tenant" onClose={() => (showCreateModal = false)}>
    <input
      type="text"
      placeholder="Tenant name"
      bind:value={newTenantName}
      on:keydown={(e) => e.key === "Enter" && handleCreate()}
    />
    {#if formError}<p class="form-error">{formError}</p>{/if}
    <button class="btn-primary" on:click={handleCreate} disabled={creating}>
      {creating ? "Creating..." : "Create Tenant"}
    </button>
  </Modal>
{/if}

{#if showApiKeyModal}
  <Modal title="Tenant Created" onClose={() => (showApiKeyModal = false)}>
    <p>Copy your API key now. It will not be shown again.</p>
    <div class="api-key-box">{createdApiKey}</div>
    <button
      class="btn-primary"
      on:click={() => navigator.clipboard.writeText(createdApiKey)}
    >
      Copy to Clipboard
    </button>
    <button class="btn-ghost" on:click={() => (showApiKeyModal = false)}
      >Done</button
    >
  </Modal>
{/if}

{#if showLoginModal}
  <Modal
    title="Login as {selectedTenant?.name}"
    onClose={() => (showLoginModal = false)}
  >
    <p>Enter the API key for this tenant.</p>
    <input
      type="text"
      placeholder="API Key"
      bind:value={loginApiKey}
      on:keydown={(e) => e.key === "Enter" && handleLogin()}
    />
    {#if formError}<p class="form-error">{formError}</p>{/if}
    <button class="btn-primary" on:click={handleLogin} disabled={loggingIn}>
      {loggingIn ? "Validating..." : "Login"}
    </button>
  </Modal>
{/if}

{#if showEditModal}
  <Modal title="Edit Tenant" onClose={() => (showEditModal = false)}>
    <input
      type="text"
      placeholder="Tenant name"
      bind:value={editTenantName}
      on:keydown={(e) => e.key === "Enter" && handleEdit()}
    />
    {#if formError}<p class="form-error">{formError}</p>{/if}
    <button class="btn-primary" on:click={handleEdit}>Save Changes</button>
  </Modal>
{/if}

<style>
  .home {
    max-width: 1100px;
    margin: 0 auto;
    min-height: 100vh;
    padding: var(--space-5);
  }

  header {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: var(--space-3);

    margin-bottom: var(--space-5);
    padding-bottom: var(--space-3);

    border-bottom: 1px solid var(--border-light);
  }

  .brand {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }

  .brand h1 {
    margin: 0;
    font-size: 2.25rem;
    font-weight: 700;
    letter-spacing: -0.03em;
  }

  .brand p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.95rem;
  }

  .btn-primary {
    display: inline-flex;
    align-items: center;
    justify-content: center;

    min-height: 44px;
    padding: 0 1.4rem;

    background: var(--accent);
    color: white;

    border-radius: var(--radius-md);

    font-size: 0.95rem;
    font-weight: 600;

    box-shadow: var(--shadow-sm);

    transition:
      background var(--transition),
      transform var(--transition),
      box-shadow var(--transition);
  }

  .btn-primary:hover {
    background: var(--accent-hover);
    box-shadow: var(--shadow-md);
    transform: translateY(-1px);
  }

  .btn-primary:disabled {
    opacity: 0.55;
    cursor: not-allowed;
    transform: none;
    box-shadow: none;
  }

  .btn-ghost {
    display: inline-flex;
    justify-content: center;
    align-items: center;

    min-height: 44px;

    padding: 0 1.4rem;

    background: var(--surface);

    color: var(--text-secondary);

    border: 1px solid var(--border-light);

    border-radius: var(--radius-md);

    transition:
      background var(--transition),
      border-color var(--transition);
  }

  .btn-ghost:hover {
    background: var(--surface-hover);
    border-color: var(--border);
  }

  main {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .tenant-list {
    display: grid;
    gap: var(--space-3);
  }

  .empty-state,
  .state-message {
    background: var(--surface);

    border: 1px solid var(--border-light);

    border-radius: var(--radius-lg);

    box-shadow: var(--shadow-sm);

    padding: 5rem 2rem;

    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;

    gap: 1rem;

    text-align: center;
  }

  .empty-state h2 {
    margin: 0;
  }

  .empty-state p,
  .state-message {
    color: var(--text-secondary);
  }

  .state-message.error {
    color: var(--danger);
    border-color: rgba(217, 83, 79, 0.25);
  }

  input {
    margin: 0.75rem 0 1rem;
  }

  .api-key-box {
    width: 100%;

    margin: 1rem 0;

    padding: 1rem;

    background: var(--surface-alt);

    border: 1px solid var(--border-light);

    border-radius: var(--radius-md);

    font-family: var(--font-mono);

    font-size: 0.9rem;

    line-height: 1.6;

    word-break: break-word;

    overflow-wrap: anywhere;

    user-select: all;
  }

  .form-error {
    margin-bottom: 1rem;

    color: var(--danger);

    font-size: 0.9rem;

    font-weight: 500;
  }

  @media (max-width: 768px) {
    .home {
      padding: var(--space-3);
    }

    header {
      flex-direction: column;
      align-items: stretch;
      gap: var(--space-2);
    }

    .btn-primary,
    .btn-ghost {
      width: 100%;
    }

    .empty-state,
    .state-message {
      padding: 3rem 1.5rem;
    }
  }
</style>
