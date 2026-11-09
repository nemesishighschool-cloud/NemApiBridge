package com.ialocalbridge

/** Separates admitted requests from future requests, and prevents a new request
 * from starting while a stop operation may still click the target application. */
class CancellationGate {
    private val lock = java.lang.Object()
    private var epoch = 0L
    private var stopsInProgress = 0
    fun ticket(): Long = synchronized(lock) { epoch }
    fun cancel(onCancel: () -> Boolean): Boolean = synchronized(lock) {
        epoch++
        onCancel()
    }
    fun beginStop(onCancel: () -> Boolean): Boolean = synchronized(lock) {
        epoch++
        stopsInProgress++
        onCancel()
    }
    fun endStop() = synchronized(lock) {
        check(stopsInProgress > 0)
        stopsInProgress--
        lock.notifyAll()
    }
    fun <T> begin(ticket: Long, onBegin: () -> T): T = synchronized(lock) {
        while (stopsInProgress > 0) lock.wait()
        if (ticket != epoch) throw AutomationException("cancelled", "La requête a été annulée avant son exécution.")
        onBegin()
    }
}
