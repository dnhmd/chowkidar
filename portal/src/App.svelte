<script>
  import Router from "svelte-spa-router";
  import Home from "./pages/Home.svelte";
  import Tenant from "./pages/Tenant.svelte";

  const routes = {
    "/": Home,
    "/tenant": Tenant,
  };
</script>

<Router {routes} />
