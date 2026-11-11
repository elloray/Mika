package com.wangzan101.mika.personal

import java.io.File
import java.io.IOException

internal data class PersonalReplayFrame(val atMs: Long, val file: File)

/** A local, low-frame-rate visual record. The oldest inactive replay is removed first. */
internal class PersonalReplayStore(
    private val root: File,
    private val limitBytes: Long = 100_000_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val active = mutableSetOf<String>()
    private var recordings = readRecordings()
    private var bytesUsed = recordings.values.flatten().sumOf { it.file.length() }

    @Synchronized fun begin(id: String) {
        require(Regex("[A-Za-z0-9_-]+").matches(id))
        active += id
    }

    @Synchronized fun end(id: String) {
        active -= id
    }

    @Synchronized fun frames(id: String): List<PersonalReplayFrame> = recordings[id]?.toList().orEmpty()

    @Synchronized fun append(id: String, bytes: ByteArray): Boolean {
        if (id !in active || bytes.isEmpty() || bytes.size > limitBytes) return false
        while (bytesUsed + bytes.size > limitBytes) {
            val oldest = recordings.entries.filter { it.key !in active }.minByOrNull { it.value.first().atMs }
                ?: return false
            val size = oldest.value.sumOf { it.file.length() }
            if (!File(root, oldest.key).deleteRecursively()) {
                recordings = readRecordings()
                bytesUsed = recordings.values.flatten().sumOf { it.file.length() }
                return false
            }
            recordings.remove(oldest.key)
            bytesUsed -= size
        }
        val folder = File(root, id)
        val at = maxOf(clock(), (recordings[id]?.lastOrNull()?.atMs ?: 0) + 1)
        val frame = File(folder, "$at.jpg")
        val pending = File(folder, ".pending")
        return try {
            folder.mkdirs()
            pending.writeBytes(bytes)
            if (!pending.renameTo(frame)) return false
            recordings.getOrPut(id) { mutableListOf() }.add(PersonalReplayFrame(at, frame))
            bytesUsed += bytes.size
            true
        } catch (_: IOException) {
            false
        } finally {
            pending.delete()
        }
    }

    private fun readRecordings(): MutableMap<String, MutableList<PersonalReplayFrame>> =
        root.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { folder ->
            File(folder, ".pending").delete()
            val frames = folder.listFiles().orEmpty().mapNotNull { file ->
                if (file.extension == "jpg" && file.isFile) {
                    file.nameWithoutExtension.toLongOrNull()?.let { PersonalReplayFrame(it, file) }
                } else null
            }.sortedBy { it.atMs }
            frames.takeIf { it.isNotEmpty() }?.let { folder.name to it.toMutableList() }
        }.toMap().toMutableMap()
}
