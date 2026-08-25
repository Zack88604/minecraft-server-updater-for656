package com.zack88604.autoupdater.application;

/** Receives immutable business events from one {@link UpdateService} run. */
@FunctionalInterface
public interface UpdateListener {

    /** Receive the next update event on the thread that runs the use case. */
    void onUpdateEvent(UpdateEvent event);
}
