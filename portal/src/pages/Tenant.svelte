<script>
    import { onMount } from "svelte";
    import { push } from "svelte-spa-router";
    import { getSession, clearSession } from "../lib/session.js";
    import Dashboard from "../components/tenant/Dashboard.svelte";
    import Routes from "../components/tenant/Routes.svelte";
    import IpRules from "../components/tenant/IpRules.svelte";
    import Settings from "../components/tenant/Settings.svelte";

    let session = null;
    let activeTab = "dashboard";

    const tabs = [
        { id: "dashboard", label: "Dashboard" },
        { id: "routes", label: "Routes" },
        { id: "ip-rules", label: "IP Rules" },
        { id: "settings", label: "Settings" },
    ];

    onMount(() => {
        session = getSession();
        if (!session) {
            push("/");
        }
    });

    function logout() {
        clearSession();
        push("/");
    }
</script>

{#if session}
    <div class="tenant-layout">
        <header>
            <div class="brand">
                <h1>Chowkidar</h1>
                <span class="separator">|</span>
                <span class="tenant-name">{session.tenantName}</span>
            </div>
            <button class="btn-logout" on:click={logout}>Logout</button>
        </header>

        {#if session.isDeprecated}
            <div class="deprecated-banner">
                You are logged in with a rotated key. Please update to your new
                API key in Settings.
            </div>
        {/if}

        <nav>
            {#each tabs as tab}
                <button
                    class="tab"
                    class:active={activeTab === tab.id}
                    on:click={() => (activeTab = tab.id)}
                >
                    {tab.label}
                </button>
            {/each}
        </nav>

        <main>
            {#if activeTab === "dashboard"}
                <Dashboard {session} />
            {:else if activeTab === "routes"}
                <Routes {session} />
            {:else if activeTab === "ip-rules"}
                <IpRules {session} />
            {:else if activeTab === "settings"}
                <Settings {session} />
            {/if}
        </main>
    </div>
{/if}

<style>
    .tenant-layout {
        min-height: 100vh;
        background: var(--bg);
        display: flex;
        flex-direction: column;
    }

    header {
        position: sticky;
        top: 0;
        z-index: 100;

        display: flex;
        justify-content: space-between;
        align-items: center;

        padding: 1.25rem 2rem;

        background: rgba(255, 255, 255, 0.94);
        backdrop-filter: blur(12px);

        border-bottom: 1px solid var(--border-light);

        box-shadow: var(--shadow-sm);
    }

    .brand {
        display: flex;
        align-items: center;
        gap: 0.9rem;
    }

    .brand h1 {
        margin: 0;
        font-size: 1.45rem;
        font-weight: 700;
        letter-spacing: -0.03em;
    }

    .separator {
        color: var(--border);
        font-size: 1rem;
        user-select: none;
    }

    .tenant-name {
        color: var(--text-secondary);
        font-weight: 600;
        font-size: 0.95rem;
        letter-spacing: 0.01em;
    }

    .btn-logout {
        display: inline-flex;
        align-items: center;
        justify-content: center;

        min-height: 40px;

        padding: 0 1rem;

        background: var(--surface);

        color: var(--text-secondary);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-md);

        font-size: 0.9rem;
        font-weight: 500;

        transition:
            background var(--transition),
            border-color var(--transition),
            color var(--transition),
            box-shadow var(--transition);
    }

    .btn-logout:hover {
        background: #fff5f5;
        border-color: var(--danger);
        color: var(--danger);
        box-shadow: var(--shadow-sm);
    }

    .deprecated-banner {
        margin: 1.5rem auto 0;

        width: calc(100% - 4rem);
        max-width: 1200px;

        padding: 1rem 1.25rem;

        border: 1px solid rgba(213, 157, 31, 0.25);

        border-radius: var(--radius-md);

        background: #fff9eb;

        color: #8a6412;

        font-size: 0.9rem;
        font-weight: 500;

        box-shadow: var(--shadow-sm);
    }

    nav {
        display: flex;
        align-items: center;
        gap: 0.5rem;

        width: calc(100% - 4rem);
        max-width: 1200px;

        margin: 1.5rem auto 0;

        padding: 0.45rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        overflow-x: auto;
    }

    .tab {
        display: inline-flex;
        align-items: center;
        justify-content: center;

        white-space: nowrap;

        padding: 0.8rem 1.25rem;

        border-radius: var(--radius-md);

        background: transparent;

        color: var(--text-secondary);

        font-size: 0.92rem;
        font-weight: 600;

        transition:
            background var(--transition),
            color var(--transition),
            box-shadow var(--transition);
    }

    .tab:hover {
        background: var(--surface-hover);
        color: var(--text-primary);
    }

    .tab.active {
        background: var(--accent);
        color: white;
        box-shadow: var(--shadow-sm);
    }

    main {
        flex: 1;

        width: 100%;
        max-width: 1200px;

        margin: 2rem auto;

        padding: 0 2rem;
    }

    @media (max-width: 768px) {
        header {
            padding: 1rem 1.25rem;
            flex-direction: column;
            align-items: flex-start;
            gap: 1rem;
        }

        .brand {
            flex-wrap: wrap;
        }

        .btn-logout {
            width: 100%;
        }

        nav {
            width: calc(100% - 2rem);
            margin-top: 1rem;
        }

        main {
            padding: 0 1rem;
            margin-top: 1.5rem;
        }

        .deprecated-banner {
            width: calc(100% - 2rem);
        }
    }
</style>
