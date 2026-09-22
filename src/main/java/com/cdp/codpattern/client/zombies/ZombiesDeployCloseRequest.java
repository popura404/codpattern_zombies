package com.cdp.codpattern.client.zombies;

import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket.Action;

import java.util.concurrent.atomic.AtomicLong;

/** Waits for the matching save/discard response before leaving the editor. */
public final class ZombiesDeployCloseRequest {
    public enum Outcome { WAIT, CLOSE, KEEP_EDITING }

    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();
    private Action pending;
    private long pendingId;

    public long begin(Action action) {
        if (action != Action.SAVE_DRAFT && action != Action.DISCARD_DRAFT) {
            throw new IllegalArgumentException("Expected save or discard action");
        }
        pending = action;
        pendingId = NEXT_REQUEST_ID.incrementAndGet();
        return pendingId;
    }

    public Outcome accept(Action responseAction, long requestId, String statusCode, boolean dirty) {
        if (!matches(responseAction, requestId)) {
            return Outcome.WAIT;
        }
        boolean succeeded = pending == Action.SAVE_DRAFT
                ? "draft.saved".equals(statusCode) || "draft.empty".equals(statusCode)
                : "draft.discarded".equals(statusCode);
        pending = null;
        return succeeded && !dirty ? Outcome.CLOSE : Outcome.KEEP_EDITING;
    }

    public boolean matches(Action responseAction, long requestId) {
        return pending != null && responseAction == pending && requestId == pendingId;
    }

    public void cancel() {
        pending = null;
    }
}
