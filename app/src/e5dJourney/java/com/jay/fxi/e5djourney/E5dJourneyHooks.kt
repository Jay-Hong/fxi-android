package com.jay.fxi.e5djourney

import com.jay.fxi.ui.free.PreviewHostContext
import com.jay.fxi.ui.free.PreviewHostHooks
import com.jay.fxi.ui.free.PreviewPageMount
import com.jay.fxi.ui.free.PreviewSignalOrigin
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` C1·C3-1, `s7e5dj_j1b.codex.r1.md` question 2): the hooks the journey accessor hands the
 * preview host, one set for one host's lifetime. Host, session, mount and operation ids are numbers this process gives to the
 * real objects the seams report, by identity, inside this process's nonce. A mount reported before the host's first context
 * stays pending until that context binds it; its dispose is paired with the binding made at mount time, never re-read from the
 * host as it is now.
 *
 * J2b holds one delivery (C1): a control command arms the set for exactly the host, session and binding it currently reports;
 * the next [beforeDeliver] with that session and binding enters the hold under a one-time operation id, and only a release
 * naming that operation and the signal's token lets it go on. An arm or a hold never outlives what it was made for:
 * - a changed binding or session discards it, and a held delivery then goes on, for the session's own check to refuse;
 * - the host leaving abandons it without resuming it, since the host's composition cancels the held call;
 * - a later command naming it is refused.
 * Nothing here decides whether the delivery happens: [beforeDeliver] only waits, and [hide] runs the real hide exactly once.
 * No command is a composition key or saved state, and the hooks supply no opener, draft or entitlement.
 *
 * Commands now arrive on binder threads while the host's callbacks run on the main thread (`s7e5dj_j2b_channel.codex.r1.md`
 * R2): every change of an arm, a hold or the context is recorded under the same lock that makes it, so the record's order is
 * the order of the changes. The record's lock is only ever taken inside this one, never the other way round; nothing waits
 * while holding either.
 */
internal object E5dJourneyHooks {
    private val sets = AtomicLong()
    private val operations = AtomicLong()
    private val live = ConcurrentHashMap<Long, HookSet>()

    @Volatile
    var selections = 0L
        private set

    fun newSet(): HookSet {
        val set = HookSet(sets.incrementAndGet())
        selections = set.id
        live[set.id] = set
        E5dJourneyRecord.add("hook", "set ${set.id} selected")
        return set
    }

    internal fun nextOperation(): Long = operations.incrementAndGet()

    internal fun forget(set: HookSet) {
        live.remove(set.id)
    }

    /** The set with [id], while its host is composed. */
    fun set(id: Long): HookSet? = live[id]

    /** Every live set as the control channel reports it. */
    fun describe(): List<Map<String, Any?>> = live.values.sortedBy { it.id }.map { it.describe() }
}

internal class HookSet(val id: Long) : PreviewHostHooks {
    private val lock = Any()
    private val hosts = IdentityHashMap<Any, Long>()
    private val sessions = IdentityHashMap<Any, Long>()
    private val mounts = IdentityHashMap<PreviewPageMount, String>()
    private val pending = ArrayList<PreviewPageMount>()
    private var current: String? = null
    private var lastContext: String? = null
    private var attached = true

    /** The host's context as last reported, by this process's ids; null before the first and after it leaves. */
    private var context: Context? = null
    private var arm: Arm? = null
    private var held: Held? = null

    private data class Context(val host: Long, val session: Long, val binding: Long)
    private class Arm(val operation: Long, val context: Context)
    private class Held(val operation: Long, val session: Long, val binding: Long, val token: Long, val gate: CompletableDeferred<String>)

    fun attached() = E5dJourneyRecord.add("hook", "set $id attached")

    fun detached() {
        synchronized(lock) {
            attached = false
            context = null
            for (what in abandonLocked()) E5dJourneyRecord.add("hook", "set $id $what abandoned reason=detached")
            E5dJourneyRecord.add("hook", "set $id detached")
        }
        E5dJourneyHooks.forget(this)
    }

    /** Drops the arm and the hold without resuming the hold: the host is leaving, and its composition cancels the held call. */
    private fun abandonLocked(): List<String> {
        val gone = listOfNotNull(arm?.let { "arm op=${it.operation}" }, held?.let { "hold op=${it.operation}" })
        arm = null
        held = null
        return gone
    }

    private fun hostId(host: Any) = hosts.getOrPut(host) { hosts.size + 1L }
    private fun sessionId(session: Any) = sessions.getOrPut(session) { sessions.size + 1L }

    fun describe(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "set" to id, "attached" to attached, "host" to context?.host, "session" to context?.session,
            "binding" to context?.binding, "armed" to arm?.operation, "held" to held?.operation, "heldToken" to held?.token
        )
    }

    /** Arms the next delivery of exactly [host], [session] and [binding]; the operation id, or the reason it is refused. */
    fun arm(host: Long, session: Long, binding: Long): Pair<Long?, String?> = synchronized(lock) {
        val now = context
        when {
            !attached || now == null || now != Context(host, session, binding) -> null to "host"
            arm != null || held != null -> null to "busy"
            else -> {
                val operation = E5dJourneyHooks.nextOperation()
                arm = Arm(operation, now)
                E5dJourneyRecord.add("hook", "set $id arm op=$operation host=$host session=$session binding=$binding")
                operation to null
            }
        }
    }

    /** Lets the held delivery of [operation] and [token] go on; false if there is no such hold now. */
    fun release(operation: Long, token: Long): Boolean {
        val gate = synchronized(lock) {
            val now = held
            if (now == null || now.operation != operation || now.token != token) return false
            held = null
            E5dJourneyRecord.add("hook", "set $id release op=$operation token=$token")
            now.gate
        }
        // Resumed outside the lock: the held call records how it ended under the lock again.
        gate.complete("released")
        return true
    }

    override suspend fun hide(realHide: suspend () -> Unit) {
        E5dJourneyRecord.add("hook", "set $id hide")
        realHide()
    }

    override fun onEvent(event: String) = E5dJourneyRecord.add("hook", "set $id event $event")

    override suspend fun beforeDeliver(origin: PreviewSignalOrigin) {
        val hold = synchronized(lock) {
            val session = sessionId(origin.session)
            E5dJourneyRecord.add("hook", "set $id deliver session=$session binding=${origin.binding} token=${origin.token}")
            val armed = arm
            when {
                armed == null -> null
                armed.context.session != session || armed.context.binding != origin.binding -> {
                    arm = null
                    E5dJourneyRecord.add("hook", "set $id arm discarded op=${armed.operation} reason=origin")
                    null
                }
                else -> {
                    arm = null
                    val gate = Held(armed.operation, session, origin.binding, origin.token, CompletableDeferred())
                    held = gate
                    E5dJourneyRecord.add("hook", "set $id hold entered op=${gate.operation} token=${gate.token}")
                    gate
                }
            }
        } ?: return
        val reason = try {
            hold.gate.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (held === hold) held = null
                E5dJourneyRecord.add("hook", "set $id hold ended op=${hold.operation} reason=cancelled")
            }
            throw cancelled
        }
        synchronized(lock) { E5dJourneyRecord.add("hook", "set $id hold ended op=${hold.operation} reason=$reason") }
    }

    override fun onContext(context: PreviewHostContext?) {
        val gate = synchronized(lock) {
            val out = ArrayList<String>()
            var discardedGate: CompletableDeferred<String>? = null
            if (context == null) {
                current = null
                lastContext = null
                this.context = null
                out += "set $id context none"
                abandonLocked().forEach { out += "set $id $it abandoned reason=host-left" }
            } else {
                val hostNumber = hostId(context.host)
                val sessionNumber = sessionId(context.session)
                val host = "host=$hostNumber"
                val text = "$host session=$sessionNumber binding=${context.binding}"
                current = host
                val now = Context(hostNumber, sessionNumber, context.binding)
                this.context = now
                val bound = pending.map { mount -> bind(mount, host) }
                pending.clear()
                if (text != lastContext || bound.isNotEmpty()) {
                    lastContext = text
                    out += "set $id context $text"
                    bound.forEach { out += "set $id mount bound $it" }
                }
                arm?.let { armed ->
                    if (armed.context != now) {
                        arm = null
                        out += "set $id arm discarded op=${armed.operation} reason=context"
                    }
                }
                held?.let { waiting ->
                    if (waiting.session != now.session || waiting.binding != now.binding) {
                        held = null
                        discardedGate = waiting.gate
                        out += "set $id hold discarded op=${waiting.operation} reason=context"
                    }
                }
            }
            for (line in out) E5dJourneyRecord.add("hook", line)
            discardedGate
        }
        // A discarded hold goes on: the session's own check then refuses the delivery it was issued under.
        gate?.complete("discarded")
    }

    override fun onPage(mount: PreviewPageMount, composed: Boolean) {
        synchronized(lock) {
            val line = if (composed) {
                val host = current
                if (host == null) {
                    pending += mount
                    "set $id mount pending ${mount.tab.name}"
                } else "set $id mount ${bind(mount, host)}"
            } else {
                val name = mounts[mount] ?: if (mount in pending) "pending ${mount.tab.name}" else "unknown ${mount.tab.name}"
                pending.remove(mount)
                "set $id dispose $name"
            }
            E5dJourneyRecord.add("hook", line)
        }
    }

    private fun bind(mount: PreviewPageMount, host: String): String =
        mounts.getOrPut(mount) { "$host mount=${mounts.size + 1} tab=${mount.tab.name}" }
}
