package com.chowkidar.gateway.config;

public class GatewayPaths {

    public static boolean shouldBypassFilters(String path) {
        return isManagementPath(path) || isActuatorPath(path);
    }
    public static boolean isManagementPath(String path) {
        return path.startsWith("/management");
    }
    public static boolean isActuatorPath(String path) {
        return path.startsWith("/actuator");
    }
}
