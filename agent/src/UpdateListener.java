/**
 * Single sink for business-layer {@link UpdateEvent}s.
 *
 * No Swing types appear here; the UI layer is responsible for marshalling
 * each event onto its event dispatch thread.
 */
interface UpdateListener {

    /** Deliver one update event produced by the business layer. */
    void onUpdateEvent(UpdateEvent event);
}
