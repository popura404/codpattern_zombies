package com.cdp.codpattern.zombiesaddon;

import net.minecraftforge.fml.ModLoadingContext;

/** Enforces exact client/server addon installation symmetry before packet negotiation. */
public final class ZombiesAddonCompatibility {
    private ZombiesAddonCompatibility() {
    }

    public static void install(String localVersion) {
        ModLoadingContext.get().registerDisplayTest(
                localVersion,
                (remoteVersion, remoteIsServer) -> acceptsRemoteVersion(
                        localVersion,
                        remoteVersion,
                        remoteIsServer));
    }

    public static boolean acceptsRemoteVersion(
            String localVersion,
            String remoteVersion,
            boolean remoteIsServer
    ) {
        return remoteIsServer
                ? addonClientAcceptsServer(localVersion, remoteVersion)
                : addonServerAcceptsClient(localVersion, remoteVersion);
    }

    public static boolean addonClientAcceptsServer(String localVersion, String remoteVersion) {
        return exactMatch(localVersion, remoteVersion);
    }

    public static boolean addonServerAcceptsClient(String localVersion, String remoteVersion) {
        return exactMatch(localVersion, remoteVersion);
    }

    private static boolean exactMatch(String localVersion, String remoteVersion) {
        return localVersion != null
                && !localVersion.isBlank()
                && localVersion.equals(remoteVersion);
    }
}
