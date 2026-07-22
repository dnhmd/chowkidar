<script>
    import { onMount } from "svelte";
    import { getRouteHealth } from "../../lib/api.js";

    export let session;

    let health = [];
    let loading = true;
    let error = null;

    onMount(async () => {
        await loadHealth();
    });

    async function loadHealth() {
        loading = true;
        error = null;
        try {
            health = await getRouteHealth(session.tenantId, session.apiKey);
        } catch (e) {
            error = "Failed to load route health.";
        } finally {
            loading = false;
        }
    }

    $: healthy = health.filter((r) => r.status === "UP").length;
    $: unhealthy = health.filter((r) => r.status === "DOWN").length;
    $: unknown = health.filter((r) => r.status === "UNKNOWN").length;
</script>

<div class="dashboard">
    <div class="section-header">
        <h2>Dashboard</h2>
        <button class="btn-refresh" on:click={loadHealth}>↻ Refresh</button>
    </div>

    {#if loading}
        <p class="state-message">Loading health status...</p>
    {:else if error}
        <p class="state-message error">{error}</p>
    {:else if health.length === 0}
        <div class="empty-state">
            <h3>No routes configured</h3>
            <p>Go to Routes to create your first route.</p>
        </div>
    {:else}
        <div class="stats">
            <div class="stat-card">
                <span class="stat-value">{health.length}</span>
                <span class="stat-label">Total Routes</span>
            </div>
            <div class="stat-card healthy">
                <span class="stat-value">{healthy}</span>
                <span class="stat-label">Healthy</span>
            </div>
            <div class="stat-card unhealthy">
                <span class="stat-value">{unhealthy}</span>
                <span class="stat-label">Unhealthy</span>
            </div>
            <div class="stat-card unknown">
                <span class="stat-value">{unknown}</span>
                <span class="stat-label">Unknown</span>
            </div>
        </div>

        <div class="route-list">
            {#each health as route (route.routeId)}
                <div class="route-row">
                    <div class="route-info">
                        <span class="route-path">{route.path}</span>
                        <span class="route-upstream">{route.upstreamUrl}</span>
                    </div>
                    <div class="route-status">
                        <span
                            class="status-badge status-{route.status.toLowerCase()}"
                        >
                            {route.status}
                        </span>
                        {#if route.timestamp}
                            <span class="last-checked">
                                Last checked {new Date(
                                    route.timestamp,
                                ).toLocaleTimeString()}
                            </span>
                        {/if}
                    </div>
                </div>
            {/each}
        </div>
    {/if}
</div>

<style>
    .dashboard {
        display: flex;
        flex-direction: column;
        gap: var(--space-4);
    }

    .section-header {
        display: flex;
        align-items: center;
        justify-content: space-between;

        margin-bottom: var(--space-2);
    }

    .section-header h2 {
        margin: 0;
        font-size: 1.6rem;
        letter-spacing: -0.02em;
    }

    .btn-refresh {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;

        min-height: 40px;

        padding: 0 1rem;

        background: var(--surface);

        color: var(--accent);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-md);

        font-size: 0.9rem;
        font-weight: 600;

        transition:
            background var(--transition),
            border-color var(--transition),
            box-shadow var(--transition);
    }

    .btn-refresh:hover {
        background: var(--surface-hover);

        border-color: var(--accent);

        box-shadow: var(--shadow-sm);
    }

    .stats {
        display: grid;

        grid-template-columns: repeat(4, minmax(0, 1fr));

        gap: var(--space-3);
    }

    .stat-card {
        position: relative;

        display: flex;
        flex-direction: column;

        gap: 0.4rem;

        padding: 1.5rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        overflow: hidden;

        transition:
            transform var(--transition),
            box-shadow var(--transition);
    }

    .stat-card::before {
        content: "";

        position: absolute;

        left: 0;
        top: 0;

        width: 4px;
        height: 100%;

        background: var(--border);
    }

    .stat-card:hover {
        transform: translateY(-2px);

        box-shadow: var(--shadow-md);
    }

    .stat-card.healthy::before {
        background: var(--success);
    }

    .stat-card.unhealthy::before {
        background: var(--danger);
    }

    .stat-card.unknown::before {
        background: var(--border);
    }

    .stat-value {
        font-family: var(--font-heading);

        font-size: 2.25rem;

        line-height: 1;

        color: var(--text-primary);

        font-weight: 700;
    }

    .stat-label {
        color: var(--text-secondary);

        font-size: 0.78rem;

        font-weight: 600;

        letter-spacing: 0.08em;

        text-transform: uppercase;
    }

    .route-list {
        display: flex;

        flex-direction: column;

        gap: var(--space-2);
    }

    .route-row {
        display: flex;

        align-items: center;

        justify-content: space-between;

        gap: var(--space-3);

        padding: 1.15rem 1.25rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        transition:
            border-color var(--transition),
            box-shadow var(--transition),
            transform var(--transition);
    }

    .route-row:hover {
        border-color: var(--border);

        box-shadow: var(--shadow-md);

        transform: translateY(-1px);
    }

    .route-info {
        display: flex;

        flex-direction: column;

        gap: 0.35rem;

        min-width: 0;
    }

    .route-path {
        font-family: var(--font-mono);

        font-size: 0.95rem;

        color: var(--text-primary);

        font-weight: 600;

        overflow: hidden;

        text-overflow: ellipsis;
    }

    .route-upstream {
        font-family: var(--font-mono);

        font-size: 0.8rem;

        color: var(--text-secondary);

        overflow: hidden;

        text-overflow: ellipsis;
    }

    .route-status {
        display: flex;

        flex-direction: column;

        align-items: flex-end;

        gap: 0.45rem;

        flex-shrink: 0;
    }

    .status-badge {
        display: inline-flex;

        align-items: center;

        justify-content: center;

        min-width: 70px;

        padding: 0.35rem 0.75rem;

        border-radius: 999px;

        font-size: 0.7rem;

        font-weight: 700;

        letter-spacing: 0.08em;
    }

    .status-up {
        background: rgba(46, 139, 87, 0.12);

        color: var(--success);
    }

    .status-down {
        background: rgba(217, 83, 79, 0.12);

        color: var(--danger);
    }

    .status-unknown {
        background: var(--surface-alt);

        color: var(--text-secondary);

        border: 1px solid var(--border-light);
    }

    .last-checked {
        font-size: 0.75rem;

        color: var(--text-secondary);
    }

    .empty-state,
    .state-message {
        padding: 4rem 2rem;

        text-align: center;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        color: var(--text-secondary);
    }

    .empty-state h3 {
        margin-bottom: 0.5rem;
    }

    .state-message.error {
        color: var(--danger);

        border-color: rgba(217, 83, 79, 0.25);
    }

    @media (max-width: 900px) {
        .stats {
            grid-template-columns: repeat(2, 1fr);
        }
    }

    @media (max-width: 650px) {
        .stats {
            grid-template-columns: 1fr;
        }

        .route-row {
            flex-direction: column;

            align-items: flex-start;
        }

        .route-status {
            width: 100%;

            align-items: flex-start;
        }
    }
</style>
