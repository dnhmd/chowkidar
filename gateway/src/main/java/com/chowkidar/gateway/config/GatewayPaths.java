package com.chowkidar.gateway.config;

public class GatewayPaths {

    public static boolean shouldBypassFilters(String path) {
        return isManagementPath(path) || isActuatorPath(path) || isFrontendPath(path);
    }
    public static boolean isManagementPath(String path) {
        return path.startsWith("/management");
    }
    public static boolean isActuatorPath(String path) {
        return path.startsWith("/actuator");
    }
    public static boolean isFrontendPath(String path) {
        return path.equals("/")
                || path.startsWith("/assets")
                || path.endsWith(".html")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".ico")
                || path.endsWith(".svg");
    }
}
