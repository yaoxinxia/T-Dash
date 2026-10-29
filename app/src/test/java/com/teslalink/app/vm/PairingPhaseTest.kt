package com.teslalink.app.vm

import com.teslalink.app.vm.LinkViewModel.PairingPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPhaseTest {
    @Test fun everyActivePairingPhasePausesReconnectBeforeJobStarts() {
        assertTrue(PairingPhase.WAITING_LINK.holdsLink)
        assertTrue(PairingPhase.SENDING.holdsLink)
        assertTrue(PairingPhase.WAITING_CONFIRM.holdsLink)
    }

    @Test fun completedFailedAndCancelledPairingReleaseReconnect() {
        assertFalse(PairingPhase.PAIRED.holdsLink)
        assertFalse(PairingPhase.FAILED.holdsLink)
        assertFalse(PairingPhase.IDLE.holdsLink)
    }
}
