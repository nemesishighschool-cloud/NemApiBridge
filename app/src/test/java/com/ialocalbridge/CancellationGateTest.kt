package com.ialocalbridge

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CancellationGateTest {
    @Test fun stopInvalidatesOldTicketsButNotFutureTickets() {
        val gate = CancellationGate()
        val old = gate.ticket()
        assertFalse(gate.cancel { false })
        try { gate.begin(old) { fail("old request started") }; fail("not cancelled") }
        catch (e: AutomationException) { assertEquals("cancelled", e.errorCode) }
        val fresh = gate.ticket()
        assertEquals("ok", gate.begin(fresh) { "ok" })
    }
    @Test fun cancellationCannotSlipBetweenValidationAndStart() {
        val gate = CancellationGate(); val ticket = gate.ticket()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = pool.submit { gate.begin(ticket) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopped = pool.submit<Boolean> { gate.cancel { true } }
            assertFalse(stopped.isDone)
            release.countDown(); start.get(5, TimeUnit.SECONDS); assertTrue(stopped.get(5, TimeUnit.SECONDS))
            try { gate.begin(ticket) { fail("old request restarted") }; fail("not cancelled") }
            catch (e: AutomationException) { assertEquals("cancelled", e.errorCode) }
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun futureRequestWaitsForStopClickToFinish() {
        val gate = CancellationGate(); gate.beginStop { false }
        val fresh = gate.ticket(); val pool = Executors.newSingleThreadExecutor()
        val attempting = CountDownLatch(1)
        try {
            val next = pool.submit<String> { attempting.countDown(); gate.begin(fresh) { "started" } }
            assertTrue(attempting.await(5, TimeUnit.SECONDS))
            try { next.get(100, TimeUnit.MILLISECONDS); fail("new request started during stop") }
            catch (_: java.util.concurrent.TimeoutException) { }
            gate.endStop()
            assertEquals("started", next.get(5, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }
}
