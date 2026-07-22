<script>
  export let title = "";
  export let onClose;
</script>

<div
  class="overlay"
  role="presentation"
  on:click={onClose}
  on:keydown={(e) => e.key === "Escape" && onClose()}
>
  <!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
  <div
    class="modal"
    role="dialog"
    aria-modal="true"
    on:click|stopPropagation
    on:keydown|stopPropagation
  >
    <div class="modal-header">
      <h3>{title}</h3>
      <button class="close-btn" on:click={onClose}>✕</button>
    </div>
    <div class="modal-body">
      <slot />
    </div>
  </div>
</div>

<style>
  .overlay {
    position: fixed;
    inset: 0;

    display: flex;
    align-items: center;
    justify-content: center;

    padding: var(--space-3);

    background: rgba(24, 54, 66, 0.45);

    backdrop-filter: blur(6px);

    z-index: 1000;

    animation: fadeIn var(--transition) ease;
  }

  .modal {
    width: 100%;
    max-width: 480px;
    max-height: calc(100vh - 3rem);

    display: flex;
    flex-direction: column;

    background: var(--surface);

    border: 1px solid var(--border-light);

    border-radius: var(--radius-lg);

    box-shadow: var(--shadow-lg);

    overflow: hidden;

    animation: slideUp var(--transition-slow) ease;
  }

  .modal-header {
    display: flex;
    align-items: center;
    justify-content: space-between;

    padding: 1.25rem 1.5rem;

    border-bottom: 1px solid var(--border-light);

    background: var(--surface);
  }

  .modal-header h3 {
    margin: 0;

    font-size: 1.25rem;

    font-weight: 700;

    letter-spacing: -0.02em;
  }

  .close-btn {
    width: 36px;
    height: 36px;

    display: flex;
    align-items: center;
    justify-content: center;

    padding: 0;

    background: transparent;

    color: var(--text-secondary);

    border-radius: var(--radius-md);

    font-size: 1rem;

    transition:
      background var(--transition),
      color var(--transition);
  }

  .close-btn:hover {
    background: var(--surface-hover);

    color: var(--text-primary);
  }

  .modal-body {
    padding: 1.5rem;

    display: flex;
    flex-direction: column;

    gap: var(--space-2);

    overflow-y: auto;
  }

  @keyframes fadeIn {
    from {
      opacity: 0;
    }

    to {
      opacity: 1;
    }
  }

  @keyframes slideUp {
    from {
      opacity: 0;
      transform: translateY(12px) scale(0.98);
    }

    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }

  @media (max-width: 600px) {
    .overlay {
      padding: 1rem;
    }

    .modal {
      max-width: 100%;
      border-radius: var(--radius-md);
    }

    .modal-header {
      padding: 1rem;
    }

    .modal-body {
      padding: 1rem;
    }
  }
</style>
