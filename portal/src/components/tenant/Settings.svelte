<script>
    import { rotateApiKey } from "../../lib/api.js";
    import { saveSession, clearSession } from "../../lib/session.js";
    import { push } from "svelte-spa-router";
    import Modal from "../Modal.svelte";

    export let session;

    let showRotateModal = false;
    let newApiKey = null;
    let rotating = false;
    let error = null;

    async function handleRotate() {
        rotating = true;
        error = null;
        try {
            const response = await rotateApiKey(
                session.tenantId,
                session.apiKey,
            );
            newApiKey = response.apiKey;
            saveSession(
                session.tenantId,
                session.tenantName,
                response.apiKey,
                session.status,
                false,
            );
            showRotateModal = true;
        } catch (e) {
            error = "Failed to rotate API key.";
        } finally {
            rotating = false;
        }
    }

    function handleRotateDone() {
        showRotateModal = false;
        newApiKey = null;
    }
</script>

<div class="settings">
    <h2>Settings</h2>

    {#if session.isDeprecated}
        <div class="warning-banner">
            ⚠️ You are logged in with a deprecated key. Your old key will expire
            soon. Rotate your key below and update your credentials.
        </div>
    {/if}

    <div class="settings-section">
        <div class="settings-card">
            <div class="settings-info">
                <h3>API Key</h3>
                <p>
                    Rotate your API key to generate a new one. Your old key
                    remains valid for 12 hours after rotation.
                </p>
                {#if error}
                    <p class="form-error">{error}</p>
                {/if}
            </div>
            <button
                class="btn-warning"
                on:click={handleRotate}
                disabled={rotating}
            >
                {rotating ? "Rotating..." : "Rotate API Key"}
            </button>
        </div>
    </div>

    <div class="settings-section">
        <div class="settings-card">
            <div class="settings-info">
                <h3>Tenant Info</h3>
                <p>Tenant ID: <code>{session.tenantId}</code></p>
                <p>Tenant Name: <strong>{session.tenantName}</strong></p>
                <p>
                    Key Status: <span
                        class="status-badge {session.isDeprecated
                            ? 'deprecated'
                            : 'active'}"
                        >{session.isDeprecated ? "Deprecated" : "Active"}</span
                    >
                </p>
            </div>
        </div>
    </div>
</div>

{#if showRotateModal}
    <Modal title="API Key Rotated" onClose={handleRotateDone}>
        <p>
            Your new API key has been generated. Copy it now — it will not be
            shown again.
        </p>
        <div class="api-key-box">{newApiKey}</div>
        <p class="note">
            Your old key remains valid for 12 hours. Update your credentials
            before it expires.
        </p>
        <button
            class="btn-primary"
            on:click={() => navigator.clipboard.writeText(newApiKey)}
        >
            Copy to Clipboard
        </button>
        <button class="btn-ghost" on:click={handleRotateDone}>Done</button>
    </Modal>
{/if}

<style>
    .settings {
        display: flex;
        flex-direction: column;
        gap: var(--space-4);
    }

    .settings > h2 {
        font-size: 1.6rem;
        letter-spacing: -0.02em;
    }

    .warning-banner {
        display: flex;

        align-items: center;

        gap: 0.75rem;

        padding: 1rem 1.25rem;

        background: rgba(230, 126, 34, 0.1);

        border: 1px solid rgba(230, 126, 34, 0.35);

        border-left: 4px solid #e67e22;

        border-radius: var(--radius-lg);

        color: #8a4b08;

        font-size: 0.9rem;

        line-height: 1.5;
    }

    .settings-section {
        display: flex;

        flex-direction: column;

        gap: var(--space-2);
    }

    .settings-card {
        display: flex;

        align-items: center;

        justify-content: space-between;

        gap: var(--space-4);

        padding: 1.5rem;

        background: var(--surface);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-lg);

        box-shadow: var(--shadow-sm);

        transition:
            border-color var(--transition),
            box-shadow var(--transition);
    }

    .settings-card:hover {
        border-color: var(--border);

        box-shadow: var(--shadow-md);
    }

    .settings-info {
        display: flex;

        flex-direction: column;

        gap: 0.5rem;

        flex: 1;
    }

    .settings-info h3 {
        font-size: 1.05rem;

        margin-bottom: 0.2rem;

        letter-spacing: -0.01em;
    }

    .settings-info p {
        margin: 0;

        font-size: 0.875rem;

        color: var(--text-secondary);
    }

    code {
        display: inline-flex;

        align-items: center;

        padding: 0.25rem 0.55rem;

        background: var(--surface-alt);

        border: 1px solid var(--border-light);

        border-radius: var(--radius-sm);

        font-family: var(--font-mono);

        font-size: 0.8rem;

        color: var(--text-primary);
    }

    .status-badge {
        display: inline-flex;

        align-items: center;

        padding: 0.35rem 0.8rem;

        border-radius: 999px;

        font-size: 0.7rem;

        font-weight: 700;

        letter-spacing: 0.08em;

        text-transform: uppercase;
    }

    .status-badge.active {
        background: rgba(46, 139, 87, 0.12);

        color: #26734d;

        border: 1px solid rgba(46, 139, 87, 0.25);
    }

    .status-badge.deprecated {
        background: rgba(230, 126, 34, 0.12);

        color: #8a4b08;

        border: 1px solid rgba(230, 126, 34, 0.25);
    }

    .btn-primary,
    .btn-warning,
    .btn-ghost {
        min-height: 42px;

        padding: 0 1.25rem;

        border-radius: var(--radius-md);

        font-size: 0.875rem;

        font-weight: 600;

        white-space: nowrap;

        transition:
            background var(--transition),
            border-color var(--transition),
            box-shadow var(--transition),
            opacity var(--transition);
    }

    .btn-primary {
        background: var(--accent);

        color: white;

        box-shadow: var(--shadow-sm);
    }

    .btn-primary:hover {
        box-shadow: var(--shadow-md);

        opacity: 0.9;
    }

    .btn-warning {
        background: #e67e22;

        color: white;

        box-shadow: var(--shadow-sm);
    }

    .btn-warning:hover {
        opacity: 0.9;

        box-shadow: var(--shadow-md);
    }

    .btn-warning:disabled {
        opacity: 0.5;

        cursor: not-allowed;
    }

    .btn-ghost {
        background: var(--surface);

        color: var(--text-secondary);

        border: 1px solid var(--border-light);
    }

    .btn-ghost:hover {
        color: var(--accent);

        border-color: var(--accent);
    }

    .api-key-box {
        padding: 1rem;

        background: var(--surface-alt);

        border: 1px dashed var(--border);

        border-radius: var(--radius-md);

        font-family: var(--font-mono);

        font-size: 0.85rem;

        line-height: 1.6;

        word-break: break-all;

        color: var(--text-primary);
    }

    .note {
        padding: 0.75rem 1rem;

        background: var(--bg);

        border-radius: var(--radius-md);

        font-size: 0.8rem;

        color: var(--text-secondary);
    }

    .form-error {
        padding: 0.6rem 0.8rem;

        background: rgba(192, 57, 43, 0.08);

        border-radius: var(--radius-md);

        color: #c0392b;

        font-size: 0.875rem;

        font-weight: 500;
    }

    @media (max-width: 700px) {
        .settings-card {
            flex-direction: column;

            align-items: stretch;
        }

        .settings-card button {
            width: 100%;
        }
    }
</style>
