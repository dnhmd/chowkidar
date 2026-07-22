<script>
  export let tenant;
  export let onLogin;
  export let onDelete;
  export let onEdit;
</script>

<div class="card">
  <div class="card-info">
    <h3>{tenant.name}</h3>
    <span class="date"
      >Created {new Date(tenant.createdAt).toLocaleDateString()}</span
    >
  </div>
  <div class="card-actions">
    <button class="btn-primary" on:click={() => onLogin(tenant)}>Login</button>
    <button class="btn-ghost" on:click={() => onEdit(tenant)}>Edit</button>
    <button class="btn-danger" on:click={() => onDelete(tenant)}>Delete</button>
  </div>
</div>

<style>
  .card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);

    padding: 1.25rem 1.5rem;

    background: var(--surface);

    border: 1px solid var(--border-light);

    border-radius: var(--radius-lg);

    box-shadow: var(--shadow-sm);

    transition:
      border-color var(--transition),
      box-shadow var(--transition),
      transform var(--transition);
  }

  .card:hover {
    border-color: var(--border);
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
  }

  .card-info {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;

    min-width: 0;
  }

  .card-info h3 {
    margin: 0;

    color: var(--text-primary);

    font-size: 1.15rem;

    font-weight: 700;

    letter-spacing: -0.02em;

    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .date {
    color: var(--text-secondary);

    font-size: 0.85rem;
  }

  .card-actions {
    display: flex;
    align-items: center;

    gap: 0.5rem;

    flex-shrink: 0;
  }

  .card-actions button {
    min-height: 38px;

    padding: 0 1rem;

    font-size: 0.875rem;

    font-weight: 600;

    border-radius: var(--radius-md);

    transition:
      background var(--transition),
      border-color var(--transition),
      color var(--transition),
      box-shadow var(--transition);
  }

  .btn-primary {
    background: var(--accent);

    color: white;

    border: 1px solid var(--accent);

    box-shadow: var(--shadow-sm);
  }

  .btn-primary:hover {
    background: var(--accent-hover);

    border-color: var(--accent-hover);

    box-shadow: var(--shadow-md);
  }

  .btn-ghost {
    background: var(--surface);

    color: var(--text-secondary);

    border: 1px solid var(--border-light);
  }

  .btn-ghost:hover {
    background: var(--surface-hover);

    color: var(--accent);

    border-color: var(--accent);
  }

  .btn-danger {
    background: transparent;

    color: var(--danger);

    border: 1px solid rgba(217, 83, 79, 0.35);
  }

  .btn-danger:hover {
    background: var(--danger);

    color: white;

    border-color: var(--danger);

    box-shadow: var(--shadow-sm);
  }

  @media (max-width: 700px) {
    .card {
      flex-direction: column;

      align-items: stretch;
    }

    .card-actions {
      width: 100%;
    }

    .card-actions button {
      flex: 1;
    }
  }
</style>
