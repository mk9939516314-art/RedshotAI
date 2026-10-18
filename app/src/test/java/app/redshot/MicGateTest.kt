package app.redshot

import org.junit.Assert.*
import org.junit.Test

class MicGateTest {
    private var now = 1_000_000L
    private val gate = MicGate { now }

    private fun tick(ms: Long = 20) { now += ms }

    @Test fun openWhenModelSilentPassesEverything() {
        repeat(50) { assertEquals(MicGate.Action.PASS, gate.frame(4000.0)); tick() }
    }

    @Test fun mutedWhileModelTalksAndFor400msAfter() {
        gate.modelTalking = true
        repeat(100) { assertEquals(MicGate.Action.ZERO, gate.frame(9000.0)); tick() }
        gate.modelTalking = false; gate.tailUntil = now + 400
        repeat(19) { assertEquals(MicGate.Action.ZERO, gate.frame(9000.0)); tick() }
        tick(100)
        assertEquals("open again after the tail", MicGate.Action.PASS, gate.frame(9000.0))
    }

    @Test fun speakerNeverBargesEvenWithLoudEchoAndOwnerNoise() {
        gate.bargeEnabled = false
        gate.modelTalking = true
        repeat(400) { assertNotEquals(MicGate.Action.BARGE, gate.frame(12000.0)); tick() }
    }

    @Test fun earphonesBargeWorksAndThenMicStaysOpenForOwner() {
        gate.bargeEnabled = true
        gate.modelTalking = true
        repeat(60) { gate.frame(80.0); tick() }               // 1.2 s of quiet answer
        var barged = false
        repeat(40) { if (gate.frame(8000.0) == MicGate.Action.BARGE) { barged = true; return@repeat }; tick() }
        assertTrue(barged)
        gate.modelTalking = false                               // service reacts to the barge
        repeat(50) { assertEquals(MicGate.Action.PASS, gate.frame(5000.0)); tick() }
    }

    @Test fun gateNeverStaysShutLongerThan30s() {
        gate.modelTalking = true
        gate.frame(100.0)
        now += 31_000
        assertEquals(MicGate.Action.PASS, gate.frame(100.0))
        assertFalse(gate.modelTalking)
    }

    @Test fun echoPeakIsMeasuredForTheAnswer() {
        gate.modelTalking = true
        listOf(300.0, 2500.0, 1200.0).forEach { gate.frame(it); tick() }
        assertEquals(2500.0, gate.echoPeak, 0.001)
    }
}
