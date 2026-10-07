package com.cdp.codpattern.app.zombies.service.navigation;

/** Operational limits, never geometric reachability or maximum route distance. */
public record NavigationTuning(long roomNanos, long serverNanos, int roomExpansions,
        int roomGeometry, int serverExpansions, int serverGeometry, int sliceExpansions,
        int sliceGeometry, int residentTiles, int surfaceNodes, int fineEdges, int searchRecords,
        int failedEdges) {
    public static final int TILE_SIZE = 8;
    public static final double EPSILON = 1.0E-6;
    public static final NavigationTuning DEFAULT = new NavigationTuning(1_000_000L, 4_000_000L,
            1024, 256, 4096, 1024, 64, 16, 512, 65536, 262144, 65536, 4096);
    public NavigationTuning {
        if (roomNanos <= 0 || serverNanos <= 0 || roomExpansions <= 0 || roomGeometry <= 0
                || serverExpansions <= 0 || serverGeometry <= 0 || sliceExpansions <= 0
                || sliceGeometry <= 0 || residentTiles <= 0 || surfaceNodes <= 0
                || fineEdges <= 0 || searchRecords <= 0 || failedEdges <= 0)
            throw new IllegalArgumentException("Navigation budgets must be positive");
    }
}
