package com.cuwano.afinador.tuner

/**
 * Owns the microphone lifecycle for the shell activity.
 *
 * Capture runs only while the runtime permission is granted *and* the screen is
 * RESUMED, so the microphone is always released in `onPause`/`onStop` and the app
 * never records in the background. The session and its state are reduced into a
 * single [TunerUiState] for the Compose layer.
 *
 * Deliberately free of Android imports so it can be exercised from host tests.
 */
class TunerCaptureCoordinator(private val session: TunerSession) {

    /** Starts or stops capture to match the permission/lifecycle gate. Idempotent. */
    fun reconcile(permissionGranted: Boolean, lifecycleResumed: Boolean) {
        if (TunerPermissionPolicy.shouldCapture(permissionGranted, lifecycleResumed)) {
            session.start()
        } else {
            session.stop()
        }
    }

    /** Reduces the current permission decision and session state for the UI. */
    fun uiState(permissionGranted: Boolean, permanentlyDenied: Boolean): TunerUiState {
        if (!permissionGranted) {
            return TunerUiStateReducer.permissionNeeded(permanentlyDenied)
        }
        return when (val state = session.state) {
            is TunerSessionState.Error -> TunerUiStateReducer.audioError(state.message)
            TunerSessionState.Listening -> TunerUiStateReducer.listening(
                session.latestReading?.let { reading ->
                    // Only a live reading produced by the CURRENT run may show a note
                    // as live or tuned. A note carried over from the previous run
                    // (after a restart, before any frame arrives) is presented stale.
                    if (session.runHasLiveReading) {
                        reading
                    } else {
                        reading.copy(isLive = false, isTuned = false)
                    }
                },
            )

            TunerSessionState.Stopped -> session.latestReading
                ?.let { TunerUiStateReducer.listening(it.copy(isLive = false, isTuned = false)) }
                ?: TunerUiState.ListeningSilence
        }
    }

    /** Releases the microphone. Safe to call more than once. */
    fun release() {
        session.stop()
    }
}

/** Pure permission and lifecycle decisions shared by the coordinator and the host tests. */
object TunerPermissionPolicy {

    /**
     * A denied request is permanent only once the system stops offering the prompt:
     * the user denied after the dialog was shown and no rationale remains.
     * A never-requested permission also reports no rationale, so [hasRequested] guards it.
     */
    fun isPermanentlyDenied(
        granted: Boolean,
        shouldShowRationale: Boolean,
        hasRequested: Boolean,
    ): Boolean = !granted && hasRequested && !shouldShowRationale

    /** Capture is allowed only with the permission and while the lifecycle is RESUMED. */
    fun shouldCapture(permissionGranted: Boolean, lifecycleResumed: Boolean): Boolean =
        permissionGranted && lifecycleResumed
}
