package team.themoment.axia.desktop.updates

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Owns only marked update attempts, never arbitrary contents of the user's temporary directory. */
internal class WindowsUpdateStagingStore(
    private val root: Path = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().resolve("axia-verified-updates"),
) {
    private val active = mutableSetOf<Path>()

    init {
        require(root.isAbsolute && root.normalize() == root)
        try {
            Files.createDirectory(root)
        } catch (_: FileAlreadyExistsException) {
            // A previous application run may have left an installer waiting for its parent to exit.
        }
        require(validRoot())
    }

    @Synchronized
    fun create(): Attempt {
        check(validRoot())
        val directory = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
        active.add(directory)
        try {
            Files.writeString(directory.resolve(MARKER), MARKER_CONTENT, CREATE_NEW)
        } catch (failure: Exception) {
            active.remove(directory)
            // These are the only entries this just-created directory can own at this point.
            runCatching { Files.deleteIfExists(directory.resolve(MARKER)) }
            runCatching { Files.deleteIfExists(directory) }
            throw failure
        }
        return Attempt(directory) { release(directory) }
    }

    /** False means a managed attempt is still locked; the caller can retry without blocking the UI. */
    @Synchronized
    fun cleanupPending(): Boolean =
        try {
            if (!validRoot()) {
                false
            } else {
                Files.newDirectoryStream(root).use { paths ->
                    var complete = true
                    for (directory in paths) {
                        if (directory !in active && ownedDirectory(directory) && !remove(directory)) complete = false
                    }
                    complete
                }
            }
        } catch (_: DirectoryIteratorException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    @Synchronized
    private fun release(directory: Path) {
        active.remove(directory)
        if (validRoot() && ownedDirectory(directory)) remove(directory)
    }

    private fun validRoot(): Boolean =
        try {
            ordinaryDirectory(root) && root.toRealPath() == root
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun ownedDirectory(directory: Path): Boolean =
        try {
            val name = directory.fileName.toString()
            val marker = directory.resolve(MARKER)
            directory.parent == root &&
                runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false) &&
                ordinaryDirectory(directory) &&
                ordinaryFile(marker) &&
                Files.size(marker) == MARKER_CONTENT.length.toLong() &&
                Files.newInputStream(marker).use { it.readNBytes(MARKER_CONTENT.length + 1).contentEquals(MARKER_CONTENT.toByteArray()) }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun remove(directory: Path): Boolean =
        try {
            val entries = Files.newDirectoryStream(directory).use { it.toList() }
            if (entries.any { it.fileName.toString() !in FILES || !ordinaryFile(it) }) {
                // Unknown files, nested directories and links never become cleanup targets.
                true
            } else {
                // Windows denies deleting an executing runner. Leave its locked MSI and ownership
                // marker intact until it exits; the restarted application retries this operation.
                Files.deleteIfExists(directory.resolve("AxiaUpdateRunner.exe"))
                Files.deleteIfExists(directory.resolve("Axia.msi"))
                Files.delete(directory.resolve(MARKER))
                Files.delete(directory)
                true
            }
        } catch (_: DirectoryIteratorException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    // On Windows a junction is not necessarily a symbolic link; isOther also rejects reparse points.
    private fun ordinaryDirectory(path: Path): Boolean =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).let {
            it.isDirectory && !it.isSymbolicLink && !it.isOther
        }

    private fun ordinaryFile(path: Path): Boolean =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).let {
            it.isRegularFile && !it.isSymbolicLink && !it.isOther
        }

    class Attempt internal constructor(
        val directory: Path,
        private val cleanup: () -> Unit,
    ) : AutoCloseable {
        override fun close() = cleanup()
    }

    private companion object {
        const val MARKER = ".axia-update"
        const val MARKER_CONTENT = "Axia verified update staging\n"
        val FILES = setOf(MARKER, "AxiaUpdateRunner.exe", "Axia.msi")
    }
}
