package team.themoment.axia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.campaign.CampaignSave
import team.themoment.axia.simulation.campaign.CampaignScenario
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class CampaignSlot(
    val label: String,
    internal val file: String,
) {
    AUTO("자동저장", "auto.json"),
    FIRST("수동 저장 1", "manual-1.json"),
    SECOND("수동 저장 2", "manual-2.json"),
    THIRD("수동 저장 3", "manual-3.json"),
}

/** 각 슬롯의 완성된 파일을 같은 디렉터리에서 원자적으로 교체한다. */
internal class CampaignStore(
    private val directory: Path = defaultDirectory(),
) {
    suspend fun slots(): Map<CampaignSlot, SavedCampaign?> =
        withContext(Dispatchers.IO) {
            buildMap {
                for (slot in CampaignSlot.entries) {
                    val path = directory.resolve(slot.file)
                    if (Files.exists(path)) {
                        // 손상된 슬롯도 목록에 남긴다. 열 때 해당 파일의 오류를 보여 준다.
                        val entry =
                            try {
                                read(path).summary(path)
                            } catch (_: Exception) {
                                null
                            }
                        put(slot, entry)
                    }
                }
            }
        }

    suspend fun load(slot: CampaignSlot): CampaignSave =
        withContext(Dispatchers.IO) {
            read(directory.resolve(slot.file))
        }

    private fun read(path: Path): CampaignSave =
        try {
            Json.decodeFromString<CampaignSave>(Files.readString(path))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("저장 파일의 형식을 읽을 수 없습니다.", e)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("저장 파일의 날짜를 읽을 수 없습니다.", e)
        }

    suspend fun save(
        slot: CampaignSlot,
        campaign: CampaignSave,
    ): SavedCampaign =
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, slot.file, ".tmp")
            try {
                val bytes = ByteBuffer.wrap(Json.encodeToString(campaign).toByteArray(Charsets.UTF_8))
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                val saved = campaign.summary(temporary)
                Files.move(temporary, directory.resolve(slot.file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                saved
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

    companion object {
        private fun defaultDirectory(): Path {
            val home = Path.of(System.getProperty("user.home"))
            val os = System.getProperty("os.name")
            val base =
                when {
                    os.startsWith("Windows") -> System.getenv("LOCALAPPDATA")?.let { Path.of(it) } ?: home.resolve("AppData/Local")
                    os.startsWith("Mac", ignoreCase = true) -> home.resolve("Library/Application Support")
                    else -> System.getenv("XDG_DATA_HOME")?.let { Path.of(it) } ?: home.resolve(".local/share")
                }
            return base.resolve("Axia").resolve("campaigns")
        }
    }
}

internal data class SavedCampaign(
    val countryId: String,
    val date: LocalDate,
    val scenario: CampaignScenario,
    val savedAt: FileTime,
)

private fun CampaignSave.summary(path: Path) = SavedCampaign(start.countryId, date, start.scenario, Files.getLastModifiedTime(path))
