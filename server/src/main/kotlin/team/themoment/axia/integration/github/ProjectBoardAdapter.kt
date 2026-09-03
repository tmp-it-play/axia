package team.themoment.axia.integration.github

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.products.ProjectFieldMapping
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.products.ProjectSemanticField
import team.themoment.axia.tasks.ProjectFieldsUpdate
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskProjectContext
import team.themoment.axia.tasks.TaskStatus
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One adapter for every product/board. Runtime reads and writes use Node/Option IDs only. */
class ProjectBoardAdapter(
    private val transport: GitHubAppTransport,
) {
    fun initialMappings(snapshot: GitHubProjectSnapshot): List<ProjectFieldMapping> =
        validatedFields(snapshot).let { fields ->
            ProjectSemanticField.entries
                .map { semantic ->
                    val field =
                        fields.singleOrNull { it.requiredText("name") == initialNames.getValue(semantic) }
                            ?: throw invalidBoardConfiguration()
                    val isDate = semantic in setOf(ProjectSemanticField.START_DATE, ProjectSemanticField.TARGET_DATE)
                    if (field.requiredText("dataType") != if (isDate) "DATE" else "SINGLE_SELECT") throw invalidBoardConfiguration()
                    val expectedOptions = optionNames[semantic].orEmpty()
                    val options =
                        expectedOptions.mapValues { (_, name) ->
                            field.objects("options").singleOrNull { it.text("name") == name }?.requiredText("id")
                                ?: throw invalidBoardConfiguration()
                        }
                    ProjectFieldMapping(semantic, field.requiredText("id"), options)
                }.also { validateMappings(snapshot, it) }
        }

    fun validateMappings(
        snapshot: GitHubProjectSnapshot,
        mappings: List<ProjectFieldMapping>,
    ) {
        val fields = validatedFields(snapshot)
        if (mappings.map { it.semantic }.toSet() != ProjectSemanticField.entries.toSet() || mappings.size != 4 ||
            mappings.map { it.fieldNodeId }.distinct().size != mappings.size
        ) {
            throw invalidBoardConfiguration()
        }
        mappings.forEach { mapping ->
            val field =
                fields.singleOrNull { it.requiredText("id") == mapping.fieldNodeId }
                    ?: throw invalidBoardConfiguration()
            val isDate = mapping.semantic in setOf(ProjectSemanticField.START_DATE, ProjectSemanticField.TARGET_DATE)
            if (field.requiredText("dataType") != if (isDate) "DATE" else "SINGLE_SELECT") throw invalidBoardConfiguration()
            if (mapping.options.keys != optionNames[mapping.semantic].orEmpty().keys) throw invalidBoardConfiguration()
            if (!isDate) {
                val ids = field.objects("options").map { it.requiredText("id") }.toSet()
                if (!ids.containsAll(mapping.options.values) || mapping.options.values
                        .distinct()
                        .size != mapping.options.size
                ) {
                    throw invalidBoardConfiguration()
                }
            }
        }
    }

    private fun validatedFields(snapshot: GitHubProjectSnapshot): List<JsonObject> =
        snapshot.fields.also { fields ->
            fields.forEach { it.validateProjectField() }
            if (fields.map { it.requiredText("id") }.distinct().size != fields.size) throw GitHubApiException(invalidContract = true)
        }

    private fun invalidBoardConfiguration(): GitHubApiException =
        GitHubApiException(invalidContract = true, boardConfigurationFailure = true)

    fun context(
        project: ProjectIntegration,
        item: JsonObject,
    ): TaskProjectContext {
        val values =
            item
                .objects("allFieldValues")
                .mapNotNull { value ->
                    value.validateProjectFieldValue()
                    value.objectOrNull("field")?.text("id")?.let { it to value }
                }.toMap()

        fun option(semantic: ProjectSemanticField): String? {
            val mapping = project.fields.singleOrNull { it.semantic == semantic } ?: return null
            val value = values[mapping.fieldNodeId] ?: return null
            if (value["optionId"] == null || value["optionId"] == JsonNull) return null
            val optionId = value.requiredText("optionId")
            if (optionId.isBlank()) throw GitHubApiException(invalidContract = true)
            return mapping.options.entries
                .singleOrNull { it.value == optionId }
                ?.key
                ?: throw invalidBoardConfiguration()
        }

        fun date(semantic: ProjectSemanticField): String? {
            val mapping = project.fields.singleOrNull { it.semantic == semantic } ?: return null
            return values[mapping.fieldNodeId]?.text("date")?.also {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    throw GitHubApiException(invalidContract = true)
                }
            }
        }
        return TaskProjectContext(
            projectId = project.id,
            projectNumber = project.number,
            itemNodeId = item.requiredText("id"),
            archived = item.boolean("isArchived"),
            status =
                option(ProjectSemanticField.STATUS)?.let(TaskStatus::valueOf) ?: TaskStatus.UNSET,
            priority = option(ProjectSemanticField.PRIORITY)?.let(TaskPriority::valueOf) ?: TaskPriority.UNSET,
            startDate = date(ProjectSemanticField.START_DATE),
            targetDate = date(ProjectSemanticField.TARGET_DATE),
        )
    }

    suspend fun updateFields(
        project: ProjectIntegration,
        itemId: String,
        request: ProjectFieldsUpdate,
        dispatch: suspend (suspend () -> Unit) -> Unit,
    ) {
        project.fields.forEach { mapping ->
            val value: JsonObject? =
                when (mapping.semantic) {
                    ProjectSemanticField.STATUS -> {
                        if (request.status ==
                            TaskStatus.UNSET
                        ) {
                            null
                        } else {
                            buildJsonObject { put("singleSelectOptionId", mapping.options.getValue(request.status.name)) }
                        }
                    }

                    ProjectSemanticField.PRIORITY -> {
                        if (request.priority ==
                            TaskPriority.UNSET
                        ) {
                            null
                        } else {
                            buildJsonObject { put("singleSelectOptionId", mapping.options.getValue(request.priority.name)) }
                        }
                    }

                    ProjectSemanticField.START_DATE -> {
                        request.startDate?.let { buildJsonObject { put("date", LocalDate.parse(it).toString()) } }
                    }

                    ProjectSemanticField.TARGET_DATE -> {
                        request.targetDate?.let { buildJsonObject { put("date", LocalDate.parse(it).toString()) } }
                    }
                }
            val input =
                buildJsonObject {
                    put("projectId", requireNotNull(project.nodeId))
                    put("itemId", itemId)
                    put("fieldId", mapping.fieldNodeId)
                    put("clientMutationId", "${request.operationId}:${mapping.semantic.name}")
                    if (value != null) put("value", value)
                }
            val mutation = if (value == null) "clearProjectV2ItemFieldValue" else "updateProjectV2ItemFieldValue"
            val inputType = if (value == null) "ClearProjectV2ItemFieldValueInput!" else "UpdateProjectV2ItemFieldValueInput!"
            // Each field is an independent write and must pass the caller's current authorization guard.
            dispatch {
                transport.graphQL(
                    "mutation(${'$'}input:$inputType) { $mutation(input:${'$'}input) { clientMutationId } }",
                    buildJsonObject { put("input", input) },
                    mutation = true,
                )
            }
        }
    }

    suspend fun archive(
        project: ProjectIntegration,
        itemId: String,
        archived: Boolean,
        operationId: String,
    ) {
        val name = if (archived) "archiveProjectV2Item" else "unarchiveProjectV2Item"
        val type = if (archived) "ArchiveProjectV2ItemInput!" else "UnarchiveProjectV2ItemInput!"
        transport.graphQL(
            "mutation(${'$'}input:$type) { $name(input:${'$'}input) { item { id isArchived } } }",
            buildJsonObject {
                put(
                    "input",
                    buildJsonObject {
                        put("projectId", project.nodeId)
                        put("itemId", itemId)
                        put("clientMutationId", operationId)
                    },
                )
            },
            mutation = true,
        )
    }

    private companion object {
        // Names are used once to seed configuration. A later rename never changes an existing ID binding.
        val initialNames =
            mapOf(
                ProjectSemanticField.STATUS to "Status",
                ProjectSemanticField.PRIORITY to "Priority",
                ProjectSemanticField.START_DATE to "Start date",
                ProjectSemanticField.TARGET_DATE to "Target date",
            )
        val optionNames =
            mapOf(
                ProjectSemanticField.STATUS to mapOf("TODO" to "Todo", "IN_PROGRESS" to "In Progress", "DONE" to "Done"),
                ProjectSemanticField.PRIORITY to mapOf("HIGH" to "High", "MEDIUM" to "Medium", "LOW" to "Low", "IN_REVIEW" to "In review"),
            )
    }
}
