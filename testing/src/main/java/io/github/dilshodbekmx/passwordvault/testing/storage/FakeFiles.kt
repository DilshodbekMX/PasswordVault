package io.github.dilshodbekmx.passwordvault.testing.storage

import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import java.io.FileNotFoundException
import java.io.IOException
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * An in-memory file system behind [FilePrimitives] for the storage tests (architecture.md §8.1).
 *
 * **Handed over and durable are kept apart.** What the code under test sees is what it handed over:
 * every write, rename and delete shows at once, as the kernel's page cache does. What survives a
 * [powerCut] is only what was made durable, with POSIX's minimum: a file's bytes by the fsync inside
 * [writeNew]; the names in a directory (files created, renamed or deleted in it, and directories
 * made in it) only by [syncDir] of that directory. After a power cut each directory shows its names
 * as of its last [syncDir], and a file whose bytes were never synced holds [lostWrite]: nothing, or
 * zeros of its length, the usual shapes of a lost write. A kill ([Stop.KILL]) changes nothing on
 * disk: the next process sees everything that was handed over.
 *
 * **It can stop at every boundary.** Every call is one boundary, taken before it does anything;
 * [writeNew] has three more inside it (the file created and empty, its bytes written, its bytes
 * synced). [stopAt] makes the n-th boundary from now throw [Stopped] (an `Error`, so that no
 * `catch (e: IOException)` or `catch (e: Exception)` in the code under test stops it), after a
 * power cut when asked. A test runs a step once to count its boundaries, then once per boundary
 * from a [copy] of the same start, and opens the vault again after each stop.
 *
 * **It can fail.** [fail] makes chosen calls throw `IOException`: one file's reads always, the next
 * `syncDir`, a `writeNew` that runs out of space after half of its bytes. [lyingFileSync] and
 * [lyingDirSync] make the syncs return without making anything durable, as a drive with a volatile
 * cache does (V-S8).
 *
 * **It keeps a record.** [log] lists every call; [replacements] every rename onto an existing name
 * (rename(2) replaces it: V-S1); [deleted] every path removed.
 *
 * Paths are absolute, with `/`, and have no empty, `.` or `..` part; anything else throws
 * `IOException`. [list] gives names in an order that is neither sorted nor the order of creation,
 * so that code relying on an order fails here too.
 */
class FakeFiles : FilePrimitives {
    /** How [stopAt] stops: the process dies, or the power goes and the process with it. */
    enum class Stop { KILL, POWER_CUT }

    /** What a file whose bytes never reached the disk holds after a power cut. */
    enum class LostWrite { EMPTY, ZEROS }

    /** The calls of [FilePrimitives], for [fail]. */
    enum class Op { LIST, READ, WRITE_NEW, EXISTS, RENAME, SYNC_DIR, DELETE, MKDIR, DELETE_TREE }

    /** Thrown at the boundary chosen with [stopAt]: the process ended there. */
    class Stopped(val boundary: Int, val at: String) : Error("stopped at boundary $boundary: $at")

    private sealed class Node

    private class FileNode(var content: ByteArray, var durable: ByteArray?) : Node()

    private class DirNode(var durableEntries: TreeMap<String, Node>?) : Node() {
        val entries = TreeMap<String, Node>()
    }

    private class Fault(val op: Op, val matches: (String) -> Boolean, var times: Int)

    private var root = DirNode(TreeMap())
    private val faults = mutableListOf<Fault>()
    private val calls = mutableListOf<String>()
    private val replaced = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var passed = 0
    private var stopAtBoundary = 0
    private var stopHow = Stop.KILL

    /** What a never-synced file holds after a power cut. */
    @Volatile var lostWrite: LostWrite = LostWrite.ZEROS

    /** The fsync inside [writeNew] returns without making the bytes durable. */
    @Volatile var lyingFileSync: Boolean = false

    /** [syncDir] returns without making the names durable. */
    @Volatile var lyingDirSync: Boolean = false

    /** Called at every boundary, before a stop: index (from 1) and what is about to happen. */
    @Volatile var onBoundary: ((index: Int, at: String) -> Unit)? = null

    /** Called at the start of every call, after its boundary: a test can change the disk "meanwhile". */
    @Volatile var beforeOp: ((op: Op, path: String) -> Unit)? = null

    /** Every call so far, such as `rename /a -> /b`. */
    val log: List<String> @Synchronized get() = calls.toList()

    /** Every rename that replaced an existing name, as `from -> to`. */
    val replacements: List<String> @Synchronized get() = replaced.toList()

    /** Every path removed by [delete] or [deleteTree] (a tree with everything in it). */
    val deleted: List<String> @Synchronized get() = removed.toList()

    /** The number of boundaries passed so far. */
    val boundaries: Int @Synchronized get() = passed

    // ---------------------------------------------------------------------------------------------
    // FilePrimitives

    @Synchronized
    override fun list(dir: String): List<String>? {
        enter(Op.LIST, "list $dir", dir)
        return when (val node = nodeAt(dir)) {
            null -> null
            is FileNode -> throw IOException("not a directory")
            is DirNode -> node.entries.keys.sortedWith(compareBy<String> { it.hashCode() }.thenBy { it })
        }
    }

    @Synchronized
    override fun read(path: String, maxBytes: Int): ByteArray {
        enter(Op.READ, "read $path", path)
        return when (val node = nodeAt(path)) {
            null -> throw FileNotFoundException("no such file")
            is DirNode -> throw IOException("is a directory")
            is FileNode -> if (node.content.size > maxBytes) node.content.copyOf(maxBytes + 1) else node.content.copyOf()
        }
    }

    @Synchronized
    override fun writeNew(path: String, bytes: ByteArray) {
        val failing = enter(Op.WRITE_NEW, "writeNew $path", path, failNow = false)
        val (parent, name) = parentOf(path)
        if (parent.entries.containsKey(name)) throw IOException("exists")
        val node = FileNode(ByteArray(0), null)
        parent.entries[name] = node
        boundary("writeNew $path: created")
        if (failing) {
            node.content = bytes.copyOf(bytes.size / 2)
            throw IOException("injected failure: no space left")
        }
        node.content = bytes.copyOf()
        boundary("writeNew $path: written")
        if (!lyingFileSync) node.durable = node.content.copyOf()
        boundary("writeNew $path: synced")
    }

    @Synchronized
    override fun exists(path: String): Boolean {
        enter(Op.EXISTS, "exists $path", path)
        return nodeAt(path) != null
    }

    @Synchronized
    override fun rename(from: String, to: String) {
        enter(Op.RENAME, "rename $from -> $to", from)
        val (fromParent, fromName) = parentOf(from)
        val node = fromParent.entries[fromName] ?: throw FileNotFoundException("no such file")
        val (toParent, toName) = parentOf(to)
        if (node is DirNode && (to == from || to.startsWith("$from/"))) throw IOException("into itself")
        val existing = toParent.entries[toName]
        if (existing === node) return
        if (existing != null) {
            when {
                node is FileNode && existing is FileNode -> Unit
                node is DirNode && existing is DirNode -> if (existing.entries.isNotEmpty()) throw IOException("directory not empty")
                else -> throw IOException("not the same kind")
            }
            replaced += "$from -> $to"
        }
        fromParent.entries.remove(fromName)
        toParent.entries[toName] = node
    }

    @Synchronized
    override fun syncDir(dir: String) {
        enter(Op.SYNC_DIR, "syncDir $dir", dir)
        val node = nodeAt(dir) as? DirNode ?: throw IOException("not a directory")
        if (!lyingDirSync) node.durableEntries = TreeMap(node.entries)
    }

    @Synchronized
    override fun delete(path: String) {
        enter(Op.DELETE, "delete $path", path)
        val (parent, name) = parentOf(path)
        when (parent.entries[name]) {
            null -> throw FileNotFoundException("no such file")
            is DirNode -> throw IOException("is a directory")
            is FileNode -> {
                parent.entries.remove(name)
                removed += path
            }
        }
    }

    @Synchronized
    override fun mkdir(dir: String) {
        enter(Op.MKDIR, "mkdir $dir", dir)
        val (parent, name) = parentOf(dir)
        if (parent.entries.containsKey(name)) throw IOException("exists")
        parent.entries[name] = DirNode(null)
    }

    @Synchronized
    override fun deleteTree(dir: String) {
        enter(Op.DELETE_TREE, "deleteTree $dir", dir)
        val (parent, name) = parentOf(dir)
        val node = parent.entries[name] as? DirNode ?: throw FileNotFoundException("no such directory")
        parent.entries.remove(name)
        collect(dir, node)
    }

    private fun collect(path: String, node: Node) {
        if (node is DirNode) for ((name, child) in node.entries) collect("$path/$name", child)
        removed += path
    }

    // ---------------------------------------------------------------------------------------------
    // Stopping and failing

    /** The [boundaries]-th boundary from now stops the process, [how] it says. */
    @Synchronized
    fun stopAt(boundaries: Int, how: Stop = Stop.KILL) {
        require(boundaries >= 1) { "a boundary from now: 1 or more" }
        stopAtBoundary = passed + boundaries
        stopHow = how
    }

    /** No stop is armed any more. */
    @Synchronized
    fun clearStop() {
        stopAtBoundary = 0
    }

    /**
     * The next [times] calls of [op] on a path that [path] accepts throw `IOException` (a negative
     * [times]: every such call, until [clearFaults]). For [Op.WRITE_NEW] the file is created and half
     * of its bytes written before the failure, as when the disk fills up. For [Op.RENAME] the path is
     * the source.
     */
    @Synchronized
    fun fail(op: Op, times: Int = 1, path: (String) -> Boolean = { true }) {
        require(times != 0) { "times: 1 or more, or negative for always" }
        faults += Fault(op, path, times)
    }

    @Synchronized
    fun clearFaults() {
        faults.clear()
    }

    /** The power goes: every directory shows its names as of its last [syncDir], every file its synced bytes. */
    @Synchronized
    fun powerCut() {
        rebuild(root)
    }

    private fun rebuild(dir: DirNode) {
        dir.entries.clear()
        dir.durableEntries?.let { dir.entries.putAll(it) }
        for (node in dir.entries.values) {
            when (node) {
                is FileNode -> node.content = node.durable?.copyOf() ?: when (lostWrite) {
                    LostWrite.EMPTY -> ByteArray(0)
                    LostWrite.ZEROS -> ByteArray(node.content.size)
                }
                is DirNode -> rebuild(node)
            }
        }
    }

    private fun enter(op: Op, at: String, path: String, failNow: Boolean = true): Boolean {
        boundary(at)
        calls += at
        beforeOp?.invoke(op, path)
        val fault = faults.firstOrNull { it.op == op && it.matches(path) } ?: return false
        if (fault.times > 0 && --fault.times == 0) faults.remove(fault)
        if (failNow) throw IOException("injected failure: $at")
        return true
    }

    private fun boundary(at: String) {
        passed++
        onBoundary?.invoke(passed, at)
        if (passed == stopAtBoundary) {
            stopAtBoundary = 0
            if (stopHow == Stop.POWER_CUT) rebuild(root)
            throw Stopped(passed, at)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // For setting up and looking (no boundary, no record)

    /** Makes [dir] and every missing parent, durable, as an earlier process would have left them. */
    @Synchronized
    fun mkdirs(dir: String) {
        var node = root
        for (part in parts(dir)) {
            val next = node.entries[part]
            node = when (next) {
                is DirNode -> next
                is FileNode -> throw IOException("a file is in the way")
                null -> DirNode(TreeMap()).also {
                    node.entries[part] = it
                    node.durableEntries = TreeMap(node.entries)
                }
            }
        }
    }

    /** Puts a file at [path] (its directories made as by [mkdirs]), durable; it replaces one that is there. */
    @Synchronized
    fun put(path: String, bytes: ByteArray) {
        val parent = parts(path).dropLast(1)
        mkdirs("/" + parent.joinToString("/"))
        val (dir, name) = parentOf(path)
        dir.entries[name] = FileNode(bytes.copyOf(), bytes.copyOf())
        dir.durableEntries = TreeMap(dir.entries)
    }

    /** The bytes of the file at [path] as the code under test would read them, or null. */
    @Synchronized
    fun bytes(path: String): ByteArray? = (nodeAt(path) as? FileNode)?.content?.copyOf()

    /** Whether something is at [path] (no record). */
    @Synchronized
    fun has(path: String): Boolean = nodeAt(path) != null

    /** The names in [dir], sorted; empty when it does not exist. */
    @Synchronized
    fun names(dir: String): List<String> = (nodeAt(dir) as? DirNode)?.entries?.keys?.sorted() ?: emptyList()

    /** Damage on the disk itself: the file's bytes, durable ones included, become what [change] makes of them. */
    @Synchronized
    fun damage(path: String, change: (ByteArray) -> ByteArray) {
        val node = nodeAt(path) as? FileNode ?: throw FileNotFoundException("no such file")
        node.content = change(node.content.copyOf())
        node.durable = node.content.copyOf()
    }

    /** Every file, by path, sorted: for a test's message. */
    @Synchronized
    fun tree(): String {
        val out = StringBuilder()
        fun walk(path: String, dir: DirNode) {
            for ((name, node) in dir.entries) {
                val child = if (path == "/") "/$name" else "$path/$name"
                when (node) {
                    is FileNode -> out.append(child).append(" (").append(node.content.size).append(" bytes)\n")
                    is DirNode -> {
                        out.append(child).append("/\n")
                        walk(child, node)
                    }
                }
            }
        }
        walk("/", root)
        return out.toString()
    }

    /**
     * A copy of this file system, durable state included, with no record, no fault and no stop:
     * a fresh start for each run of a crash test.
     */
    @Synchronized
    fun copy(): FakeFiles {
        val copies = IdentityHashMap<Node, Node>()
        fun dup(node: Node): Node = copies.getOrPut(node) {
            when (node) {
                is FileNode -> FileNode(node.content.copyOf(), node.durable?.copyOf())
                is DirNode -> DirNode(null)
            }
        }
        fillAll(root, ::dup)
        val copy = FakeFiles()
        copy.root = dup(root) as DirNode
        copy.lostWrite = lostWrite
        return copy
    }

    /** Copies every directory reachable from [start], by its names now or by its durable names, through [dup]. */
    private fun fillAll(start: DirNode, dup: (Node) -> Node) {
        val seen = IdentityHashMap<DirNode, Boolean>()
        val pending = ArrayDeque<DirNode>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val source = pending.removeFirst()
            if (seen.put(source, true) != null) continue
            val target = dup(source) as DirNode
            for ((name, child) in source.entries) target.entries[name] = dup(child)
            target.durableEntries = source.durableEntries?.let { durable ->
                TreeMap<String, Node>().also { map -> for ((name, child) in durable) map[name] = dup(child) }
            }
            for (child in source.entries.values) if (child is DirNode) pending.add(child)
            for (child in source.durableEntries?.values.orEmpty()) if (child is DirNode) pending.add(child)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Paths

    private fun parts(path: String): List<String> {
        if (!path.startsWith("/")) throw IOException("not an absolute path")
        if (path == "/") return emptyList()
        val parts = path.substring(1).split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw IOException("not a plain path")
        return parts
    }

    private fun nodeAt(path: String): Node? {
        var node: Node = root
        for (part in parts(path)) {
            node = (node as? DirNode)?.entries?.get(part) ?: return null
        }
        return node
    }

    private fun parentOf(path: String): Pair<DirNode, String> {
        val parts = parts(path)
        if (parts.isEmpty()) throw IOException("the root has no parent")
        var node: Node = root
        for (part in parts.dropLast(1)) {
            node = (node as? DirNode)?.entries?.get(part) ?: throw FileNotFoundException("no such directory")
        }
        val dir = node as? DirNode ?: throw IOException("not a directory")
        return dir to parts.last()
    }
}
