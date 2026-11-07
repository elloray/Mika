package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.workspace.SandboxPaths
import java.io.File
import java.util.UUID

/** Only files explicitly exported by this task are visible to the computer. */
data class TaskFile(val id: String, val name: String, val size: Long, val sha256: String? = null)

interface RemoteTaskFiles {
    val uploads: RemoteUploads
    fun scope(owner: String): RemoteTaskFiles = ScopedRemoteTaskFiles(this, owner)
    fun start(conversation: String, goal: String, files: List<Upload>, launch: (String) -> RemoteTask): RemoteTask
    fun read(taskId: String, file: TaskFile, offset: Long): ByteArray
}

private class ScopedRemoteTaskFiles(private val delegate: RemoteTaskFiles, owner: String) : RemoteTaskFiles {
    override val uploads = delegate.uploads.scope(owner)
    override fun start(conversation: String, goal: String, files: List<Upload>, launch: (String) -> RemoteTask) =
        delegate.start(conversation, goal, files, launch)
    override fun read(taskId: String, file: TaskFile, offset: Long) = delegate.read(taskId, file, offset)
}

data class Upload(val name: String, val size: Long, val path: File)

interface RemoteUploads {
    fun scope(owner: String): RemoteUploads = this
    fun create(name: String, size: Long): String
    fun append(id: String, offset: Long, bytes: ByteArray): Long
    fun discard(id: String)
    fun <T> consume(ids: List<String>, use: (List<Upload>) -> T): T
}

/** Incomplete uploads stay outside all conversation workspaces. IDs are single use and bound to one token owner. */
class PendingUploads(
    private val directory: File,
    private val reserveFree: Long = 0,
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteUploads {
    private data class Entry(val owner: String, val upload: Upload, var touched: Long)
    private val entries = mutableMapOf<String, Entry>()

    init {
        directory.mkdirs()
        // A process restart invalidates every upload ID and removes complete and interrupted files alike.
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    override fun scope(owner: String): RemoteUploads = object : RemoteUploads {
        override fun create(name: String, size: Long) = create(owner, name, size)
        override fun append(id: String, offset: Long, bytes: ByteArray) = append(owner, id, offset, bytes)
        override fun discard(id: String) = discard(owner, id)
        override fun <T> consume(ids: List<String>, use: (List<Upload>) -> T): T = consume(owner, ids, use)
    }

    override fun create(name: String, size: Long) = create(LOCAL, name, size)
    override fun append(id: String, offset: Long, bytes: ByteArray) = append(LOCAL, id, offset, bytes)
    override fun discard(id: String) = discard(LOCAL, id)
    override fun <T> consume(ids: List<String>, use: (List<Upload>) -> T): T = consume(LOCAL, ids, use)

    @Synchronized private fun create(owner: String, name: String, size: Long): String {
        if (!SandboxPaths.plainName(name) || name.length > 255 || size !in 0..MAX_BYTES)
            invalid("Invalid attachment name or size")
        expire()
        val owned = entries.values.filter { it.owner == owner }
        if (owned.size >= MAX_PENDING || owned.sumOf { it.upload.size } + size > MAX_BYTES ||
            entries.values.sumOf { it.upload.size } + size > MAX_BYTES)
            conflict("Pending attachments exceed the transfer limit")
        if (directory.usableSpace - size < reserveFree) conflict("Not enough free space for this attachment")
        val id = UUID.randomUUID().toString()
        val path = File(directory, id)
        if (!path.createNewFile()) conflict("Could not create upload")
        entries[id] = Entry(owner, Upload(name, size, path), now())
        return id
    }

    @Synchronized private fun append(owner: String, id: String, offset: Long, bytes: ByteArray): Long {
        val entry = lookup(owner, id)
        val upload = entry.upload
        if (bytes.isEmpty() || bytes.size > CHUNK_BYTES) invalid("Invalid attachment chunk size")
        if (offset != upload.path.length() || offset < 0 || bytes.size > upload.size - offset)
            conflict("Attachment offset or size changed")
        if (directory.usableSpace - bytes.size < reserveFree) conflict("Not enough free space for this attachment")
        upload.path.appendBytes(bytes)
        entry.touched = now()
        return offset + bytes.size
    }

    @Synchronized private fun discard(owner: String, id: String) {
        val entry = entries[id] ?: return
        if (entry.owner == owner) remove(id)
    }

    private fun <T> consume(owner: String, ids: List<String>, use: (List<Upload>) -> T): T {
        if (ids.size !in 1..MAX_FILES || ids.distinct().size != ids.size) invalid("Invalid attachment selection")
        val files = synchronized(this) {
            val selected = ids.map { lookup(owner, it).upload }
            if (selected.sumOf { it.size } > MAX_BYTES) conflict("Attachments exceed the transfer limit")
            if (selected.any { !it.path.isFile || it.path.length() != it.size }) conflict("Attachment upload is incomplete")
            ids.forEach(entries::remove)
            selected
        }
        return try { use(files) } finally { files.forEach { it.path.delete() } }
    }

    private fun lookup(owner: String, id: String): Entry {
        val entry = entries[id]
        if (entry == null || entry.touched < now() - EXPIRY_MS || !entry.upload.path.isFile) {
            if (entry != null) remove(id)
            throw RemoteTaskError.General(RemoteTaskError.Code.NOT_FOUND, "Upload not found or expired")
        }
        // Never reveal or remove another token's pending upload when an ID is guessed.
        if (entry.owner != owner) throw RemoteTaskError.General(RemoteTaskError.Code.NOT_FOUND, "Upload not found or expired")
        return entry
    }

    private fun expire() {
        val expired = now() - EXPIRY_MS
        entries.filterValues { it.touched < expired }.keys.toList().forEach(::remove)
    }

    private fun remove(id: String) { entries.remove(id)?.upload?.path?.delete() }
    private fun invalid(text: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, text)
    private fun conflict(text: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, text)

    companion object {
        const val MAX_FILES = 9
        const val MAX_BYTES = 200L shl 20
        const val CHUNK_BYTES = 128 * 1024
        const val EXPIRY_MS = 30 * 60_000L
        private const val MAX_PENDING = 32
        private const val LOCAL = "local"
    }
}
