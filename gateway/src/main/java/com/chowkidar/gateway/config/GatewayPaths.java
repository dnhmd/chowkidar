package com.chowkidar.gateway.config;

public class GatewayPaths {

    public static boolean isManagementPath(String path) {
        return path.startsWith("/management");
    }
}
