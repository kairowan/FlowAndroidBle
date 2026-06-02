package com.flowble.internal

internal class ActiveObservationStore<Key, Session> {

    sealed interface AcquireResult<out Session> {
        data object CreateNew : AcquireResult<Nothing>
        data class Existing<Session>(val session: Session) : AcquireResult<Session>
    }

    private data class Entry<Session>(
        val session: Session,
        val kind: ObservationKind,
        var referenceCount: Int
    )

    private val entries = mutableMapOf<Key, Entry<Session>>()

    fun acquire(key: Key, kind: ObservationKind): AcquireResult<Session> {
        val entry = entries[key] ?: return AcquireResult.CreateNew
        if (entry.kind != kind) {
            throw IllegalStateException(
                "Conflicting observation already active. Existing=${entry.kind.label}, requested=${kind.label}"
            )
        }
        entry.referenceCount += 1
        return AcquireResult.Existing(entry.session)
    }

    fun register(key: Key, kind: ObservationKind, session: Session): Session {
        entries[key] = Entry(
            session = session,
            kind = kind,
            referenceCount = 1
        )
        return session
    }

    fun release(key: Key, session: Session): Boolean {
        val entry = entries[key] ?: return false
        if (entry.session != session) {
            return false
        }

        entry.referenceCount -= 1
        if (entry.referenceCount > 0) {
            return false
        }

        entries.remove(key)
        return true
    }

    fun remove(key: Key, session: Session): Boolean {
        val entry = entries[key] ?: return false
        if (entry.session != session) {
            return false
        }
        entries.remove(key)
        return true
    }

    fun referenceCount(key: Key): Int? = entries[key]?.referenceCount
}
