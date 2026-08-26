package com.zack88604.autoupdater.gui.javafx;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Dual-channel outbox to one JavaFX helper JVM (v2.1 transport).
 *
 * <p>Two independent channels share one writer thread that drains them onto the
 * helper's stdin:
 *
 * <ul>
 *   <li><b>Control channel</b> — FIFO, never coalesced: {@code init}, {@code open},
 *       {@code close}, {@code exit}. Strict ordering matters, so every control
 *       message is queued and written in order.</li>
 *   <li><b>State channel</b> — a single latest-wins slot holding the current
 *       {@code state} snapshot. A newer snapshot replaces an older one the writer
 *       has not flushed yet, so intermediate states coalesce.</li>
 * </ul>
 *
 * <p><b>Transport invariant</b> (第一阶段重构 §强制约束 3): a {@code SUCCESS}/{@code ERROR}
 * terminal snapshot must never be silently dropped ahead of a {@code close}/{@code exit}.
 * When {@link #shutdown} runs while the state slot holds a terminal snapshot, that
 * snapshot is promoted to a protected slot and the writer flushes it before the
 * flow-ending control messages. Non-terminal snapshots may coalesce and are cleared
 * by {@link #shutdown}, exactly as v2.1 specifies. The invariant is exercised by
 * {@code TransportMailboxTest}.</p>
 *
 * <p>The writer drains the protected slot, then the control FIFO, then the state
 * slot — so {@code open} always precedes the first {@code state}, and a protected
 * terminal snapshot always precedes {@code close}/{@code exit}. Every method is
 * non-blocking: a stalled helper never blocks the update flow (pending state
 * coalesces into the single slot and is dropped once full).</p>
 *
 * <p>This class is pure mailbox state — it owns no threads. {@link JavaFxHelperProcess}
 * runs the writer loop that calls {@link #pollForWrite()}.</p>
 */
final class TransportMailbox {

    /** One control message, plus whether it ends the helper flow. */
    private static final class Control {
        final String json;
        final boolean endsFlow;

        Control(String json, boolean endsFlow) {
            this.json = json;
            this.endsFlow = endsFlow;
        }
    }

    private final Deque<Control> control = new ArrayDeque<>();
    private String stateSlot;
    private boolean stateSlotTerminal;
    private String protectedState;
    private boolean closed;

    /** Offer the complete JSON snapshot for the state channel.
     *
     * @param terminal whether the snapshot is a SUCCESS/ERROR terminal state that
     *                 must survive an imminent close/exit
     */
    synchronized void postState(String json, boolean terminal) {
        if (closed) {
            return;
        }
        stateSlot = json;
        stateSlotTerminal = terminal;
    }

    /** Offer a control message on the FIFO channel (init/open). */
    synchronized void postControl(String json) {
        if (closed) {
            return;
        }
        control.add(new Control(json, false));
    }

    /**
     * Terminate the flow: refuse further posts and queue {@code close} then
     * {@code exit}. A terminal snapshot currently in the state slot is protected
     * (flushed first); a non-terminal one is cleared — the terminal state must
     * never be silently dropped, intermediate state may coalesce (constraint 3).
     */
    synchronized void shutdown(String closeJson, String exitJson) {
        if (closed) {
            return;
        }
        closed = true;
        if (stateSlot != null && stateSlotTerminal) {
            protectedState = stateSlot;
        }
        stateSlot = null;
        stateSlotTerminal = false;
        control.add(new Control(closeJson, true));
        control.add(new Control(exitJson, true));
    }

    /**
     * Pull the next line the writer should emit, or {@code null} when idle.
     * Order: protected terminal → control FIFO → state slot. Draining a
     * flow-ending control message clears the state slot, so a snapshot that races
     * in after a close/exit can never be written after it.
     */
    synchronized String pollForWrite() {
        if (protectedState != null) {
            String line = protectedState;
            protectedState = null;
            return line;
        }
        if (!control.isEmpty()) {
            Control c = control.poll();
            if (c.endsFlow) {
                stateSlot = null;
                stateSlotTerminal = false;
            }
            return c.json;
        }
        if (stateSlot != null) {
            String line = stateSlot;
            stateSlot = null;
            stateSlotTerminal = false;
            return line;
        }
        return null;
    }

    /** Whether the writer still has pending work. */
    synchronized boolean hasPending() {
        return protectedState != null || !control.isEmpty() || stateSlot != null;
    }

    /** Whether {@link #shutdown} has been requested. */
    synchronized boolean isClosed() {
        return closed;
    }
}
