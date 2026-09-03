package team.themoment.axia.updates

import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.server.http.content.LocalPathContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/**
 * A deliberately narrow public view of the release publisher's directory.
 * Construct before creating the runtime so an invalid configured root fails startup.
 * The publisher owns these directories and must keep versioned installers immutable.
 */
class PublishedUpdates(
    private val root: Path,
) {
    init {
        require(root.isAbsolute && root.normalize() == root && root.parent != null) {
            "AXIA_UPDATES_ROOT must be an absolute normalized directory below the filesystem root"
        }
        require(readableRoot()) {
            "AXIA_UPDATES_ROOT must be a readable, searchable directory without symbolic links"
        }
    }

    internal suspend fun content(rawPath: String): OutgoingContent? =
        withContext(Dispatchers.IO) {
            val artifact = artifact(rawPath) ?: return@withContext null
            if (!readableRoot()) return@withContext null
            var path = root
            artifact.segments.forEachIndexed { index, segment ->
                path = path.resolve(segment)
                val attributes = attributes(path) ?: return@withContext null
                val last = index == artifact.segments.lastIndex
                if (attributes.isSymbolicLink || !Files.isReadable(path)) return@withContext null
                if (last && !attributes.isRegularFile) return@withContext null
                if (!last && (!attributes.isDirectory || !Files.isExecutable(path))) return@withContext null
            }
            if (artifact.isFeed) {
                // Open once: an atomic feed rename cannot change either these bytes or their length.
                // NOFOLLOW_LINKS also rejects a leaf replaced with a link between validation and open.
                val bytes =
                    Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { channel ->
                        Channels.newInputStream(channel).readNBytes(MAX_FEED_BYTES + 1)
                    }
                if (bytes.size > MAX_FEED_BYTES) throw IOException("Published update feed exceeds the size limit")
                ByteArrayContent(bytes, artifact.contentType)
            } else {
                // Ktor opens a streaming channel and seeks for Range requests; no installer-sized allocation.
                LocalPathContent(path, artifact.contentType)
            }
        }

    private fun readableRoot(): Boolean {
        var directory = root.root
        if (!readableDirectory(directory)) return false
        for (segment in root) {
            directory = directory.resolve(segment)
            if (!readableDirectory(directory)) return false
        }
        return true
    }

    private fun readableDirectory(directory: Path): Boolean =
        Files.isDirectory(directory, NOFOLLOW_LINKS) && Files.isReadable(directory) && Files.isExecutable(directory)

    private fun attributes(path: Path): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        } catch (_: NotDirectoryException) {
            null
        }

    private fun artifact(rawPath: String): Artifact? {
        // Inspect the original URI, not Ktor's decoded route parameters. No encoded aliases are public.
        val segments = rawPath.split('/')
        if (segments.size !in 5..6 || segments[0].isNotEmpty() || segments[1] != "updates") return null
        val extension =
            when (segments[2] to segments[3]) {
                "macos" to "arm64" -> "dmg"
                "windows" to "x64" -> "msi"
                else -> return null
            }
        if (segments.size == 5) {
            if (segments[4] != "appcast.xml") return null
            return Artifact(segments.drop(2), ContentType.Application.Xml, isFeed = true)
        }
        val version = segments[4]
        if (!canonicalVersion(version) || segments[5] != "Axia-$version.$extension") return null
        val contentType =
            if (extension == "dmg") {
                ContentType("application", "x-apple-diskimage")
            } else {
                ContentType("application", "x-msi")
            }
        return Artifact(segments.drop(2), contentType, isFeed = false)
    }

    private fun canonicalVersion(version: String): Boolean {
        if (!VERSION.matches(version)) return false
        val components = version.split('.').map { it.toIntOrNull() ?: return false }
        // Keep the same canonical, MSI-compatible range as scripts/release/prepare_native.py.
        return components[0] <= 255 && components[1] <= 255 && components[2] <= 65535 && components.any { it != 0 }
    }

    private data class Artifact(
        val segments: List<String>,
        val contentType: ContentType,
        val isFeed: Boolean,
    )

    private companion object {
        const val MAX_FEED_BYTES = 1024 * 1024
        val VERSION = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    }
}
