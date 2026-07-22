<script>
    import { onMount } from "svelte";
    import {
        getIpRules,
        createIpRule,
        updateIpRule,
        deleteIpRule,
    } from "../../lib/api.js";
    import Modal from "../Modal.svelte";

    export let session;

    let rules = [];
    let loading = true;
    let error = null;

    let showCreateModal = false;
    let showEditModal = false;
    let selectedRule = null;
    let formError = null;

    let newRule = { ipAddress: "", action: "BLOCK" };
    let editAction = "BLOCK";

    onMount(loadRules);

    async function loadRules() {
        loading = true;
        error = null;
        try {
            rules = await getIpRules(session.tenantId, session.apiKey);
        } catch (e) {
            error = "Failed to load IP rules.";
        } finally {
            loading = false;
        }
    }

    async function handleCreate() {
        if (!newRule.ipAddress.trim()) return;
        formError = null;
        try {
            await createIpRule(session.tenantId, session.apiKey, newRule);
            showCreateModal = false;
            newRule = { ipAddress: "", action: "BLOCK" };
            await loadRules();
        } catch (e) {
            formError = "Failed to create rule.";
        }
    }

    async function handleUpdate() {
        formError = null;
        try {
            await updateIpRule(
                session.tenantId,
                session.apiKey,
                selectedRule.id,
                { action: editAction },
            );
            showEditModal = false;
            await loadRules();
        } catch (e) {
            formError = "Failed to update rule.";
        }
    }

    async function handleDelete(rule) {
        if (!confirm(`Delete rule for ${rule.ipAddress}?`)) return;
        try {
            await deleteIpRule(session.tenantId, session.apiKey, rule.id);
            await loadRules();
        } catch (e) {
            error = "Failed to delete rule.";
        }
    }

    function openEdit(rule) {
        selectedRule = rule;
        editAction = rule.action;
        formError = null;
        showEditModal = true;
    }
</script>

<div class="ip-rules">
    <div class="section-header">
        <h2>IP Rules</h2>
        <button
            class="btn-primary"
            on:click={() => {
                showCreateModal = true;
                formError = null;
            }}
        >
            + Add Rule
        </button>
    </div>

    <div class="info-box">
        <p>
            <strong>Blocklist mode:</strong> All IPs allowed except explicitly blocked
            ones.
        </p>
        <p>
            <strong>Allowlist mode:</strong> Activated when any ALLOW rule exists
            — only listed IPs pass.
        </p>
        <p><strong>BLOCK always wins</strong> over ALLOW for the same IP.</p>
    </div>

    {#if loading}
        <p class="state-message">Loading rules...</p>
    {:else if error}
        <p class="state-message error">{error}</p>
    {:else if rules.length === 0}
        <div class="empty-state">
            <h3>No IP rules</h3>
            <p>
                All traffic is currently allowed. Add a rule to restrict access.
            </p>
        </div>
    {:else}
        <div class="rule-list">
            {#each rules as rule (rule.id)}
                <div class="rule-row">
                    <span class="ip-address">{rule.ipAddress}</span>
                    <span
                        class="action-badge action-{rule.action.toLowerCase()}"
                        >{rule.action}</span
                    >
                    <div class="rule-actions">
                        <button
                            class="btn-ghost"
                            on:click={() => openEdit(rule)}>Edit</button
                        >
                        <button
                            class="btn-danger"
                            on:click={() => handleDelete(rule)}>Delete</button
                        >
                    </div>
                </div>
            {/each}
        </div>
    {/if}
</div>

{#if showCreateModal}
    <Modal title="Add IP Rule" onClose={() => (showCreateModal = false)}>
        <input
            type="text"
            placeholder="IP Address (e.g. 192.168.1.100)"
            bind:value={newRule.ipAddress}
        />
        <label>
            Action
            <select bind:value={newRule.action}>
                <option value="BLOCK">BLOCK</option>
                <option value="ALLOW">ALLOW</option>
            </select>
        </label>
        {#if formError}<p class="form-error">{formError}</p>{/if}
        <button class="btn-primary" on:click={handleCreate}>Add Rule</button>
    </Modal>
{/if}

{#if showEditModal && selectedRule}
    <Modal
        title="Edit Rule for {selectedRule.ipAddress}"
        onClose={() => (showEditModal = false)}
    >
        <label>
            Action
            <select bind:value={editAction}>
                <option value="BLOCK">BLOCK</option>
                <option value="ALLOW">ALLOW</option>
            </select>
        </label>
        {#if formError}<p class="form-error">{formError}</p>{/if}
        <button class="btn-primary" on:click={handleUpdate}>Save Changes</button
        >
    </Modal>
{/if}

<style>
    .ip-rules {
        display: flex;
        flex-direction: column;
        gap: var(--space-4);
    }

    .section-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    .section-header h2 {
        margin: 0;
        font-size: 1.6rem;
        letter-spacing: -0.02em;
    }

    .info-box {
        display: flex;
        flex-direction: column;
        gap: 0.6rem;

        padding: 1.25rem 1.5rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        position: relative;
        overflow: hidden;
    }

    .info-box::before {
        content: "";

        position: absolute;

        left: 0;
        top: 0;

        width: 4px;
        height: 100%;

        background: var(--accent);
    }

    .info-box p {
        margin: 0;

        color: var(--text-secondary);

        font-size: 0.9rem;
    }

    .info-box strong {
        color: var(--text-primary);
    }

    .rule-list {
        display: flex;
        flex-direction: column;

        gap: var(--space-2);
    }

    .rule-row {
        display: flex;

        align-items: center;

        gap: var(--space-3);

        padding: 1.1rem 1.25rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        transition:
            border-color var(--transition),
            box-shadow var(--transition),
            transform var(--transition);
    }

    .rule-row:hover {
        border-color: var(--border);

        box-shadow: var(--shadow-md);

        transform: translateY(-1px);
    }

    .ip-address {
        flex: 1;

        font-family: var(--font-mono);

        font-size: 0.95rem;

        font-weight: 600;

        color: var(--text-primary);
    }

    .action-badge {
        display: inline-flex;

        align-items: center;

        justify-content: center;

        min-width: 80px;

        padding: 0.35rem 0.85rem;

        border-radius: 999px;

        font-size: 0.7rem;

        font-weight: 700;

        letter-spacing: 0.08em;
    }

    .action-block {
        background: rgba(217, 83, 79, 0.12);

        color: var(--danger);
    }

    .action-allow {
        background: rgba(46, 139, 87, 0.12);

        color: var(--success);
    }

    .rule-actions {
        display: flex;

        align-items: center;

        gap: 0.5rem;
    }

    .btn-primary {
        min-height: 42px;

        padding: 0 1.25rem;

        background: var(--accent);

        color: white;

        border-radius: var(--radius-md);

        font-size: 0.9rem;

        font-weight: 600;

        box-shadow: var(--shadow-sm);

        transition:
            background var(--transition),
            box-shadow var(--transition);
    }

    .btn-primary:hover {
        background: var(--accent-hover);

        box-shadow: var(--shadow-md);
    }

    .btn-ghost {
        min-height: 38px;

        padding: 0 1rem;

        background: var(--surface);

        color: var(--text-secondary);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-md);

        font-size: 0.85rem;

        font-weight: 600;

        transition:
            color var(--transition),
            border-color var(--transition),
            background var(--transition);
    }

    .btn-ghost:hover {
        color: var(--accent);

        border-color: var(--accent);

        background: var(--surface-hover);
    }

    .btn-danger {
        min-height: 38px;

        padding: 0 1rem;

        background: transparent;

        color: var(--danger);

        border: 1px solid rgba(217, 83, 79, 0.35);

        border-radius: var(--radius-md);

        font-size: 0.85rem;

        font-weight: 600;

        transition:
            background var(--transition),
            color var(--transition),
            border-color var(--transition);
    }

    .btn-danger:hover {
        background: var(--danger);

        border-color: var(--danger);

        color: white;
    }

    .empty-state,
    .state-message {
        display: flex;

        flex-direction: column;

        align-items: center;

        justify-content: center;

        gap: 1rem;

        padding: 4rem 2rem;

        text-align: center;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        color: var(--text-secondary);
    }

    .empty-state h3 {
        margin: 0;
    }

    .state-message.error {
        color: var(--danger);

        border-color: rgba(217, 83, 79, 0.25);
    }

    label {
        display: flex;

        flex-direction: column;

        gap: 0.5rem;

        color: var(--text-secondary);

        font-size: 0.9rem;

        font-weight: 500;
    }

    input,
    select {
        width: 100%;

        padding: 0.75rem 0.9rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-md);

        color: var(--text-primary);

        transition:
            border-color var(--transition),
            box-shadow var(--transition);
    }

    input:focus,
    select:focus {
        border-color: var(--accent);

        box-shadow: 0 0 0 3px rgba(115, 98, 138, 0.15);
    }

    .form-error {
        color: var(--danger);

        font-size: 0.875rem;

        font-weight: 500;
    }

    @media (max-width: 700px) {
        .rule-row {
            flex-direction: column;

            align-items: stretch;
        }

        .rule-actions {
            width: 100%;
        }

        .rule-actions button {
            flex: 1;
        }
    }
</style>
