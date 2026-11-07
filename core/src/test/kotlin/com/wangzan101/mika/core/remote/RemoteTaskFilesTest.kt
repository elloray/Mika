package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

class RemoteTaskFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun incompleteAndRepeatedReferencesNeverLaunchAPartialTask() {
        val uploads = PendingUploads(temp.newFolder())
        val empty = uploads.create("空.csv", 0)
        val other = uploads.create("图.png", 3)
        assertThrows(RemoteTaskError::class.java) { uploads.append(other, 1, byteArrayOf(1)) }
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(empty, other)) { fail() } }
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(empty, empty)) { fail() } }
        assertEquals(3L, uploads.append(other, 0, byteArrayOf(0, -1, 3)))
        uploads.consume(listOf(empty, other)) {
            assertArrayEquals(byteArrayOf(0, -1, 3), it[1].path.readBytes())
            assertEquals(0, it[0].path.length())
        }
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(other)) { fail() } }
    }

    @Test fun quotasNamesExpiryAndRejectedSubmissionsReleaseTheStagingFiles() {
        var time = 0L
        val dir = temp.newFolder()
        val uploads = PendingUploads(dir) { time }
        assertThrows(RemoteTaskError::class.java) { uploads.create("../escape", 1) }
        val old = uploads.create("old.dat", PendingUploads.MAX_BYTES)
        assertThrows(RemoteTaskError::class.java) { uploads.create("over", 1) }
        time += PendingUploads.EXPIRY_MS + 1
        val fresh = uploads.create("new.csv", 0)
        assertThrows(RemoteTaskError::class.java) { uploads.consume(listOf(old)) { fail() } }
        assertThrows(IllegalStateException::class.java) { uploads.consume(listOf(fresh)) { error("launch refused") } }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun filesRequireAuthenticationAndBelongToOneSpecificRemoteTask() {
        val fake = FakeRemoteTasks()
        val one = (fake.start("first", null) as StartOutcome.Started).task
        val two = (fake.start("next", one.conversation) as StartOutcome.Started).task
        fake.tasks[one.id] = one.copy(files = listOf(TaskFile("csv", "result.csv", 3)))
        val access = object : RemoteTaskFiles {
            override val uploads = PendingUploads(temp.newFolder())
            override fun start(conversation: String, goal: String, files: List<Upload>, launch: (String) -> RemoteTask) = error("unused")
            override fun read(taskId: String, file: TaskFile, offset: Long) = byteArrayOf(1, 2, 3).copyOfRange(offset.toInt(), 3)
        }
        val tasks = object : RemoteTasks by fake {
            override val fileAccess = access
            override fun scope(owner: String): RemoteTasks {
                val scoped = fake.scope(owner)
                return object : RemoteTasks by scoped { override val fileAccess = access.scope(owner) }
            }
        }
        val host = RemoteHost(tasks, { "unit-file-test" }, 0, false, "test")
        fun read(id: String, offset: Int = 0, auth: Boolean = true): HttpResponse = host.handle(HttpRequest("GET",
            "/api/v1/tasks/$id/files/csv", if (auth) mapOf("authorization" to "Bearer unit-file-test") else emptyMap(),
            byteArrayOf(), InetAddress.getLoopbackAddress(), "offset=$offset"))
        assertEquals(401, read(one.id, auth = false).status)
        assertEquals(404, read(two.id).status)
        assertEquals(400, read(one.id, 4).status)
        assertEquals("\"AgM=\"", Json.parseToJsonElement(read(one.id, 1).body.decodeToString()).jsonObject["data"].toString())
    }

    @Test fun uploadIdsAreOwnerBoundAndRestartRemovesInterruptedBytes() {
        val dir = temp.newFolder()
        val uploads = PendingUploads(dir)
        val one = uploads.scope("one")
        val two = uploads.scope("two")
        val id = one.create("private.bin", 4)
        assertEquals(2L, one.append(id, 0, byteArrayOf(1, 2)))
        assertThrows(RemoteTaskError::class.java) { two.append(id, 2, byteArrayOf(3, 4)) }
        assertThrows(RemoteTaskError::class.java) { two.consume(listOf(id)) { fail() } }
        two.discard(id)
        assertEquals(4L, one.append(id, 2, byteArrayOf(3, 4)))
        assertEquals(4L, dir.listFiles()!!.single().length())

        val restarted = PendingUploads(dir)
        assertTrue(dir.listFiles()!!.isEmpty())
        assertThrows(RemoteTaskError::class.java) { restarted.scope("one").consume(listOf(id)) { fail() } }
    }

    @Test fun crossTokenTaskReadsMutationsAndResultDownloadsAreDenied() {
        val fake = FakeRemoteTasks()
        val access = object : RemoteTaskFiles {
            override val uploads = PendingUploads(temp.newFolder())
            override fun start(conversation: String, goal: String, files: List<Upload>, launch: (String) -> RemoteTask) = error("unused")
            override fun read(taskId: String, file: TaskFile, offset: Long) = byteArrayOf(1)
        }
        val tasks = object : RemoteTasks by fake {
            override val fileAccess = access
            override fun scope(owner: String): RemoteTasks {
                val scoped = fake.scope(owner)
                return object : RemoteTasks by scoped { override val fileAccess = access.scope(owner) }
            }
        }
        var token = "one"
        val host = RemoteHost(tasks, { token }, 0, false, "test")
        fun request(path: String, method: String = "GET", body: ByteArray = byteArrayOf(), query: String = "") = host.handle(HttpRequest(method, path,
            mapOf("authorization" to "Bearer $token", "content-type" to "application/json"), body, InetAddress.getLoopbackAddress(), query))
        val created = request("/api/v1/tasks", "POST", """{"goal":"private"}""".toByteArray())
        assertEquals(201, created.status)
        val conversation = Json.parseToJsonElement(created.body.decodeToString()).jsonObject.getValue("conversation")
        fake.tasks["t1"] = fake.tasks.getValue("t1").copy(files = listOf(TaskFile("out", "out.txt", 1)))
        token = "two"
        assertEquals(404, request("/api/v1/tasks", "POST", """{"goal":"intrude","conversation":$conversation}""".toByteArray()).status)
        assertEquals(404, request("/api/v1/tasks/t1").status)
        assertEquals(404, request("/api/v1/tasks/t1/answer", "POST", """{"text":"no"}""".toByteArray()).status)
        assertEquals(404, request("/api/v1/tasks/t1/steer", "POST", """{"text":"no"}""".toByteArray()).status)
        assertEquals(404, request("/api/v1/tasks/t1/cancel", "POST", "{}".toByteArray()).status)
        assertEquals(404, request("/api/v1/tasks/t1/files/out", query = "offset=0").status)
        assertEquals("[]", Json.parseToJsonElement(request("/api/v1/tasks").body.decodeToString()).jsonObject["tasks"].toString())
        assertTrue(fake.answers.isEmpty() && fake.steered.isEmpty() && fake.cancelled.isEmpty())
    }
}
