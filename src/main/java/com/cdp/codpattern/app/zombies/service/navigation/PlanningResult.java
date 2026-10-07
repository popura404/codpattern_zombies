package com.cdp.codpattern.app.zombies.service.navigation;

public record PlanningResult(Status status, Reason reason, RoutePlan plan) {
    public enum Status { READY, PENDING, WAITING_CHUNK, UNREACHABLE }
    public enum Reason { NONE, BUILD, BUDGET, RESOURCE_LIMITED, CHUNK_UNLOADED, DISCONNECTED }
    public PlanningResult {
        if ((status == Status.READY) != (plan != null)) throw new IllegalArgumentException("READY requires a route");
    }
    public static PlanningResult pending(Reason reason) { return new PlanningResult(Status.PENDING, reason, null); }
    public static PlanningResult waitingChunk() { return new PlanningResult(Status.WAITING_CHUNK, Reason.CHUNK_UNLOADED, null); }
    public static PlanningResult unreachable() { return new PlanningResult(Status.UNREACHABLE, Reason.DISCONNECTED, null); }
    public static PlanningResult ready(RoutePlan plan) { return new PlanningResult(Status.READY, Reason.NONE, plan); }
}
