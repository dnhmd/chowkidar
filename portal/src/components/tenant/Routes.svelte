<script>
    import { onMount } from "svelte";
    import {
        getRoutes,
        createRoute,
        deleteRoute,
        updateRouteUrl,
        updateRouteRate,
        updateRouteTimeout,
        updateRouteIdempotency,
    } from "../../lib/api.js";
    import Modal from "../Modal.svelte";

    export let session;

    let routes = [];
    let loading = true;
    let error = null;

    // Modal state
    let showCreateModal = false;
    let showEditModal = false;
    let selectedRoute = null;
    let activeEditTab = "url";
    let formError = null;

    // Create form
    let newRoute = {
        path: "",
        upstreamUrl: "",
        fallbackUrl: "",
        timeoutMs: 3000,
        capacity: 100,
        refillRate: 10,
        volumeLimit: 10000,
        windowSize: 3600,
        requiresIdempotency: false,
    };

    // Edit form
    let editUrl = { upstreamUrl: "", fallbackUrl: "" };
    let editRate = {
        capacity: 0,
        refillRate: 0,
        volumeLimit: 0,
        windowSize: 0,
    };
    let editTimeout = { timeoutMs: 0 };
    let editIdempotency = { requiresIdempotency: false };

    onMount(loadRoutes);

    async function loadRoutes() {
        loading = true;
        error = null;
        try {
            routes = await getRoutes(session.tenantId, session.apiKey);
        } catch (e) {
            error = "Failed to load routes.";
        } finally {
            loading = false;
        }
    }

    async function handleCreate() {
        formError = null;
        try {
            await createRoute(session.tenantId, session.apiKey, newRoute);
            showCreateModal = false;
            resetNewRoute();
            await loadRoutes();
        } catch (e) {
            formError = "Failed to create route.";
        }
    }

    async function handleDelete(route) {
        if (!confirm(`Delete route "${route.path}"?`)) return;
        try {
            await deleteRoute(session.tenantId, session.apiKey, route.id);
            await loadRoutes();
        } catch (e) {
            error = "Failed to delete route.";
        }
    }

    function openEdit(route) {
        selectedRoute = route;
        activeEditTab = "url";
        editUrl = {
            upstreamUrl: route.upstreamUrl,
            fallbackUrl: route.fallbackUrl || "",
        };
        editRate = {
            capacity: route.capacity,
            refillRate: route.refillRate,
            volumeLimit: route.volumeLimit,
            windowSize: route.windowSize,
        };
        editTimeout = { timeoutMs: route.timeoutMs };
        editIdempotency = { requiresIdempotency: route.requiresIdempotency };
        formError = null;
        showEditModal = true;
    }

    async function handleEditSave() {
        formError = null;
        try {
            if (activeEditTab === "url") {
                await updateRouteUrl(
                    session.tenantId,
                    session.apiKey,
                    selectedRoute.id,
                    editUrl,
                );
            } else if (activeEditTab === "rate") {
                await updateRouteRate(
                    session.tenantId,
                    session.apiKey,
                    selectedRoute.id,
                    editRate,
                );
            } else if (activeEditTab === "timeout") {
                await updateRouteTimeout(
                    session.tenantId,
                    session.apiKey,
                    selectedRoute.id,
                    editTimeout,
                );
            } else if (activeEditTab === "idempotency") {
                await updateRouteIdempotency(
                    session.tenantId,
                    session.apiKey,
                    selectedRoute.id,
                    editIdempotency,
                );
            }
            showEditModal = false;
            await loadRoutes();
        } catch (e) {
            formError = "Failed to save changes.";
        }
    }

    function resetNewRoute() {
        newRoute = {
            path: "",
            upstreamUrl: "",
            fallbackUrl: "",
            timeoutMs: 3000,
            capacity: 100,
            refillRate: 10,
            volumeLimit: 10000,
            windowSize: 3600,
            requiresIdempotency: false,
        };
    }
</script>

<div class="routes">
    <div class="section-header">
        <h2>Routes</h2>
        <button
            class="btn-primary"
            on:click={() => {
                showCreateModal = true;
                formError = null;
            }}
        >
            + New Route
        </button>
    </div>

    {#if loading}
        <p class="state-message">Loading routes...</p>
    {:else if error}
        <p class="state-message error">{error}</p>
    {:else if routes.length === 0}
        <div class="empty-state">
            <h3>No routes yet</h3>
            <p>Create your first route to start proxying traffic.</p>
            <button
                class="btn-primary"
                on:click={() => (showCreateModal = true)}>+ New Route</button
            >
        </div>
    {:else}
        <div class="route-list">
            {#each routes as route (route.id)}
                <div class="route-card">
                    <div class="route-main">
                        <span class="route-path">{route.path}</span>
                        <span class="route-upstream">{route.upstreamUrl}</span>
                        {#if route.fallbackUrl}
                            <span class="route-fallback"
                                >Fallback: {route.fallbackUrl}</span
                            >
                        {/if}
                    </div>
                    <div class="route-meta">
                        <span class="badge">{route.capacity} tokens</span>
                        <span class="badge">{route.timeoutMs}ms</span>
                        {#if route.requiresIdempotency}
                            <span class="badge accent">Idempotent</span>
                        {/if}
                    </div>
                    <div class="route-actions">
                        <button
                            class="btn-ghost"
                            on:click={() => openEdit(route)}>Edit</button
                        >
                        <button
                            class="btn-danger"
                            on:click={() => handleDelete(route)}>Delete</button
                        >
                    </div>
                </div>
            {/each}
        </div>
    {/if}
</div>

{#if showCreateModal}
    <Modal title="New Route" onClose={() => (showCreateModal = false)}>
        <input
            type="text"
            placeholder="Path (e.g. /api)"
            bind:value={newRoute.path}
        />
        <input
            type="text"
            placeholder="Upstream URL"
            bind:value={newRoute.upstreamUrl}
        />
        <input
            type="text"
            placeholder="Fallback URL (optional)"
            bind:value={newRoute.fallbackUrl}
        />
        <div class="form-row">
            <label
                >Timeout (ms)
                <input type="number" bind:value={newRoute.timeoutMs} />
            </label>
            <label
                >Capacity
                <input type="number" bind:value={newRoute.capacity} />
            </label>
        </div>
        <div class="form-row">
            <label
                >Refill Rate
                <input type="number" bind:value={newRoute.refillRate} />
            </label>
            <label
                >Volume Limit
                <input type="number" bind:value={newRoute.volumeLimit} />
            </label>
        </div>
        <div class="form-row">
            <label
                >Window Size (s)
                <input type="number" bind:value={newRoute.windowSize} />
            </label>
        </div>
        <label class="checkbox-label">
            <input
                type="checkbox"
                bind:checked={newRoute.requiresIdempotency}
            />
            Requires Idempotency
        </label>
        {#if formError}<p class="form-error">{formError}</p>{/if}
        <button class="btn-primary" on:click={handleCreate}>Create Route</button
        >
    </Modal>
{/if}

{#if showEditModal && selectedRoute}
    <Modal
        title="Edit {selectedRoute.path}"
        onClose={() => (showEditModal = false)}
    >
        <div class="edit-tabs">
            <button
                class="edit-tab"
                class:active={activeEditTab === "url"}
                on:click={() => (activeEditTab = "url")}>URLs</button
            >
            <button
                class="edit-tab"
                class:active={activeEditTab === "rate"}
                on:click={() => (activeEditTab = "rate")}>Rate Limits</button
            >
            <button
                class="edit-tab"
                class:active={activeEditTab === "timeout"}
                on:click={() => (activeEditTab = "timeout")}>Timeout</button
            >
            <button
                class="edit-tab"
                class:active={activeEditTab === "idempotency"}
                on:click={() => (activeEditTab = "idempotency")}
                >Idempotency</button
            >
        </div>

        {#if activeEditTab === "url"}
            <input
                type="text"
                placeholder="Upstream URL"
                bind:value={editUrl.upstreamUrl}
            />
            <input
                type="text"
                placeholder="Fallback URL (optional)"
                bind:value={editUrl.fallbackUrl}
            />
        {:else if activeEditTab === "rate"}
            <div class="form-row">
                <label
                    >Capacity <input
                        type="number"
                        bind:value={editRate.capacity}
                    /></label
                >
                <label
                    >Refill Rate <input
                        type="number"
                        bind:value={editRate.refillRate}
                    /></label
                >
            </div>
            <div class="form-row">
                <label
                    >Volume Limit <input
                        type="number"
                        bind:value={editRate.volumeLimit}
                    /></label
                >
                <label
                    >Window Size (s) <input
                        type="number"
                        bind:value={editRate.windowSize}
                    /></label
                >
            </div>
        {:else if activeEditTab === "timeout"}
            <label
                >Timeout (ms) <input
                    type="number"
                    bind:value={editTimeout.timeoutMs}
                /></label
            >
        {:else if activeEditTab === "idempotency"}
            <label class="checkbox-label">
                <input
                    type="checkbox"
                    bind:checked={editIdempotency.requiresIdempotency}
                />
                Requires Idempotency
            </label>
        {/if}

        {#if formError}<p class="form-error">{formError}</p>{/if}
        <button class="btn-primary" on:click={handleEditSave}
            >Save Changes</button
        >
    </Modal>
{/if}

<style>
    .routes {
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

    .route-list {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
    }

    .route-card {
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

    .route-card:hover {
        border-color: var(--border);

        box-shadow: var(--shadow-md);

        transform: translateY(-2px);
    }

    .route-main {
        display: flex;
        flex-direction: column;

        gap: 0.4rem;

        flex: 1;

        min-width: 0;
    }

    .route-path {
        font-family: var(--font-mono);

        font-size: 1rem;

        font-weight: 700;

        color: var(--text-primary);

        letter-spacing: -0.02em;
    }

    .route-upstream {
        font-family: var(--font-mono);

        font-size: 0.8rem;

        color: var(--text-secondary);

        overflow: hidden;

        text-overflow: ellipsis;

        white-space: nowrap;
    }

    .route-fallback {
        font-family: var(--font-mono);

        font-size: 0.75rem;

        color: var(--accent);
    }

    .route-meta {
        display: flex;

        align-items: center;

        justify-content: center;

        gap: 0.5rem;

        flex-wrap: wrap;
    }

    .badge {
        display: inline-flex;

        align-items: center;

        white-space: nowrap;

        padding: 0.35rem 0.7rem;

        background: var(--surface-alt);

        border: 1px solid var(--border-light);

        border-radius: 999px;

        color: var(--text-secondary);

        font-size: 0.7rem;

        font-weight: 600;

        letter-spacing: 0.04em;
    }

    .badge.accent {
        background: rgba(115, 98, 138, 0.12);

        border-color: rgba(115, 98, 138, 0.3);

        color: var(--accent);
    }

    .route-actions {
        display: flex;

        gap: 0.5rem;

        flex-shrink: 0;
    }

    .btn-primary {
        min-height: 42px;

        padding: 0 1.25rem;

        background: var(--accent);

        color: white;

        border-radius: var(--radius-md);

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
    }

    .btn-danger:hover {
        background: var(--danger);

        color: white;

        border-color: var(--danger);
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

    .state-message.error {
        color: var(--danger);

        border-color: rgba(217, 83, 79, 0.25);
    }

    input {
        width: 100%;

        padding: 0.75rem 0.9rem;

        background: var(--surface);

        color: var(--text-primary);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-md);

        transition:
            border-color var(--transition),
            box-shadow var(--transition);
    }

    input:focus {
        border-color: var(--accent);

        box-shadow: 0 0 0 3px rgba(115, 98, 138, 0.15);
    }

    .form-row {
        display: grid;

        grid-template-columns: repeat(2, 1fr);

        gap: var(--space-2);
    }

    label {
        display: flex;

        flex-direction: column;

        gap: 0.5rem;

        color: var(--text-secondary);

        font-size: 0.875rem;

        font-weight: 500;
    }

    .checkbox-label {
        flex-direction: row;

        align-items: center;

        gap: 0.75rem;

        cursor: pointer;
    }

    .checkbox-label input {
        width: auto;
    }

    .form-error {
        color: var(--danger);

        font-size: 0.875rem;

        font-weight: 500;
    }

    .edit-tabs {
        display: flex;

        gap: 0.25rem;

        padding: 0.35rem;

        margin-bottom: var(--space-3);

        background: var(--surface-alt);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-md);

        overflow-x: auto;
    }

    .edit-tab {
        flex: 1;

        padding: 0.65rem 1rem;

        background: transparent;

        color: var(--text-secondary);

        border-radius: var(--radius-sm);

        font-size: 0.8rem;

        font-weight: 600;

        white-space: nowrap;
    }

    .edit-tab:hover {
        background: var(--surface-hover);

        color: var(--text-primary);
    }

    .edit-tab.active {
        background: var(--surface);

        color: var(--accent);

        box-shadow: var(--shadow-sm);
    }

    @media (max-width: 900px) {
        .route-card {
            flex-direction: column;

            align-items: stretch;
        }

        .route-meta {
            justify-content: flex-start;
        }

        .route-actions button {
            flex: 1;
        }
    }

    @media (max-width: 600px) {
        .form-row {
            grid-template-columns: 1fr;
        }

        .route-actions {
            width: 100%;
        }
    }
</style>
