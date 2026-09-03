package team.themoment.axia.desktop

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A metadata-only process lock prevents two app processes from rotating the same OS credential. */
class DesktopInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        fun acquire(path: Path = defaultPath()): DesktopInstanceLock? {
            Files.createDirectories(path.parent)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) {
                    channel.close()
                    return null
                }
                return DesktopInstanceLock(channel, lock)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }

        private fun defaultPath(): Path {
            val directory =
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    Path.of(requireNotNull(System.getenv("LOCALAPPDATA")), "Axia")
                } else {
                    Path.of(System.getProperty("user.home"), "Library", "Application Support", "Axia")
                }
            // This file contains no data. Do not unlink it while an app may be using its inode.
            return directory.resolve("desktop.lock")
        }
    }
}
