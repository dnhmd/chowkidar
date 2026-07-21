<script>
  export let tenant
  export let onLogin
  export let onDelete
  export let onEdit
</script>

<div class="card">
  <div class="card-info">
    <h3>{tenant.name}</h3>
    <span class="date">Created {new Date(tenant.createdAt).toLocaleDateString()}</span>
  </div>
  <div class="card-actions">
    <button class="btn-primary" on:click={() => onLogin(tenant)}>Login</button>
    <button class="btn-ghost" on:click={() => onEdit(tenant)}>Edit</button>
    <button class="btn-danger" on:click={() => onDelete(tenant)}>Delete</button>
  </div>
</div>

<style>
  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: var(--space-3);
    display: flex;
    align-items: center;
    justify-content: space-between;
    box-shadow: var(--shadow-sm);
    transition: box-shadow 0.2s;
  }

  .card:hover {
    box-shadow: var(--shadow-md);
  }

  .card-info {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .card-info h3 {
    font-family: var(--font-heading);
    font-size: 1.1rem;
    color: var(--text-primary);
  }

  .date {
    font-size: 0.8rem;
    color: var(--text-secondary);
  }

  .card-actions {
    display: flex;
    gap: var(--space-1);
  }

  .btn-primary {
    background: var(--accent);
    color: white;
    padding: 8px 16px;
    border-radius: var(--radius-sm);
    font-size: 0.875rem;
    font-weight: 500;
    transition: opacity 0.2s;
  }

  .btn-primary:hover {
    opacity: 0.85;
  }

  .btn-ghost {
    background: transparent;
    color: var(--text-secondary);
    border: 1px solid var(--border);
    padding: 8px 16px;
    border-radius: var(--radius-sm);
    font-size: 0.875rem;
    transition: border-color 0.2s;
  }

  .btn-ghost:hover {
    border-color: var(--accent);
    color: var(--accent);
  }

  .btn-danger {
    background: transparent;
    color: #c0392b;
    border: 1px solid #c0392b;
    padding: 8px 16px;
    border-radius: var(--radius-sm);
    font-size: 0.875rem;
    transition: all 0.2s;
  }

  .btn-danger:hover {
    background: #c0392b;
    color: white;
  }
</style>