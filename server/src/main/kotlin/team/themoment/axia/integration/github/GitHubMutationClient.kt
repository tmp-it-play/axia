package team.themoment.axia.integration.github

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.products.RepositoryIntegration

/** Every call dispatches at most once. GitHub clientMutationId is correlation, never an idempotency guarantee. */
class GitHubMutationClient(
    private val transport: GitHubAppTransport,
) {
    suspend fun actor(numericId: Long): GitHubActor {
        require(numericId > 0)
        return transport.readObject("/user/$numericId").restActor().also {
            if (it.id != numericId || it.nodeId.isNullOrBlank()) throw GitHubApiException(invalidContract = true)
        }
    }

    suspend fun createIssue(
        repository: RepositoryIntegration,
        title: String,
        body: String,
        actors: List<GitHubActor>,
        labels: List<String>,
        milestone: Int?,
    ): JsonObject =
        objectMutation(
            HttpMethod.Post,
            "${path(repository)}/issues",
            buildJsonObject {
                put("title", title)
                put("body", body)
                put("assignees", strings(actors.map { it.login }))
                put("labels", strings(labels))
                if (milestone != null) put("milestone", milestone)
            },
        )

    suspend fun updateIssue(
        repository: RepositoryIntegration,
        number: Int,
        title: String?,
        body: String?,
        labels: List<String>?,
        milestone: Int?,
        writeMilestone: Boolean = true,
    ): JsonObject =
        objectMutation(
            HttpMethod.Patch,
            "${path(repository)}/issues/$number",
            buildJsonObject {
                if (title != null) put("title", title)
                if (body != null) put("body", body)
                if (labels != null) put("labels", strings(labels))
                if (writeMilestone) put("milestone", milestone?.let(::JsonPrimitive) ?: JsonNull)
            },
        )

    suspend fun issueState(
        repository: RepositoryIntegration,
        number: Int,
        open: Boolean,
    ): JsonObject =
        objectMutation(
            HttpMethod.Patch,
            "${path(repository)}/issues/$number",
            buildJsonObject { put("state", if (open) "open" else "closed") },
        )

    suspend fun comment(
        repository: RepositoryIntegration,
        number: Int,
        body: String,
    ): JsonObject = objectMutation(HttpMethod.Post, "${path(repository)}/issues/$number/comments", buildJsonObject { put("body", body) })

    suspend fun readComment(
        repository: RepositoryIntegration,
        commentId: Long,
    ): JsonObject = transport.readObject("${path(repository)}/issues/comments/$commentId")

    suspend fun changeAssignees(
        nodeId: String,
        actors: List<GitHubActor>,
        add: Boolean,
        operationId: String,
    ) {
        if (actors.isEmpty()) return
        val name = if (add) "addAssigneesToAssignable" else "removeAssigneesFromAssignable"
        val type = if (add) "AddAssigneesToAssignableInput!" else "RemoveAssigneesFromAssignableInput!"
        graph(
            name,
            type,
            buildJsonObject {
                put("assignableId", nodeId)
                put("assigneeIds", strings(actors.map { requireNotNull(it.nodeId) }))
                put("clientMutationId", operationId)
            },
            "clientMutationId",
        )
    }

    suspend fun reviewers(
        repository: RepositoryIntegration,
        number: Int,
        actors: List<GitHubActor>,
        add: Boolean,
    ) {
        if (actors.isEmpty()) return
        transport.mutate(
            if (add) HttpMethod.Post else HttpMethod.Delete,
            "${path(repository)}/pulls/$number/requested_reviewers",
            buildJsonObject {
                put("reviewers", strings(actors.map { it.login }))
            },
        )
    }

    suspend fun createDraft(
        project: ProjectIntegration,
        title: String,
        body: String,
        actors: List<GitHubActor>,
        operationId: String,
    ): JsonObject =
        graph(
            "addProjectV2DraftIssue",
            "AddProjectV2DraftIssueInput!",
            buildJsonObject {
                put("projectId", requireNotNull(project.nodeId))
                put("title", title)
                put("body", body)
                put("assigneeIds", strings(actors.map { requireNotNull(it.nodeId) }))
                put("clientMutationId", operationId)
            },
            "projectItem { id content { ... on DraftIssue { id } } }",
        ).requiredObject("projectItem")

    suspend fun updateDraft(
        nodeId: String,
        title: String?,
        body: String?,
        assignees: List<GitHubActor>?,
        operationId: String,
    ) {
        graph(
            "updateProjectV2DraftIssue",
            "UpdateProjectV2DraftIssueInput!",
            buildJsonObject {
                put("draftIssueId", nodeId)
                put("clientMutationId", operationId)
                if (title != null) put("title", title)
                if (body != null) put("body", body)
                if (assignees != null) put("assigneeIds", strings(assignees.map { requireNotNull(it.nodeId) }))
            },
            "draftIssue { id }",
        )
    }

    suspend fun convertDraft(
        itemId: String,
        repository: RepositoryIntegration,
        operationId: String,
    ): JsonObject =
        graph(
            "convertProjectV2DraftIssueItemToIssue",
            "ConvertProjectV2DraftIssueItemToIssueInput!",
            buildJsonObject {
                put("itemId", itemId)
                put("repositoryId", requireNotNull(repository.nodeId))
                put("clientMutationId", operationId)
            },
            "item { id content { ... on Issue { id number } } }",
        ).requiredObject("item")

    suspend fun addProject(
        project: ProjectIntegration,
        nodeId: String,
        operationId: String,
    ): JsonObject =
        graph(
            "addProjectV2ItemById",
            "AddProjectV2ItemByIdInput!",
            buildJsonObject {
                put("projectId", requireNotNull(project.nodeId))
                put("contentId", nodeId)
                put("clientMutationId", operationId)
            },
            "item { id }",
        ).requiredObject("item")

    /** A lost creation response has no provable identity: reread for freshness, but never infer success from matching text. */
    suspend fun inspectUncertainCreation(
        repository: RepositoryIntegration?,
        project: ProjectIntegration?,
    ) {
        if (repository !=
            null
        ) {
            transport.read(
                "${path(repository)}/issues",
                mapOf(
                    "state" to "all",
                    "per_page" to "1",
                    "sort" to "created",
                    "direction" to "desc",
                ),
            )
        }
        if (project !=
            null
        ) {
            transport.graphQL(
                "query(${'$'}id:ID!){node(id:${'$'}id){... on ProjectV2{id updatedAt}}}",
                buildJsonObject {
                    put("id", requireNotNull(project.nodeId))
                },
            )
        }
    }

    private suspend fun graph(
        name: String,
        inputType: String,
        input: JsonObject,
        fields: String,
    ): JsonObject =
        transport
            .graphQL(
                "mutation(${'$'}input:$inputType){$name(input:${'$'}input){$fields}}",
                buildJsonObject {
                    put("input", input)
                },
                mutation = true,
            ).requiredObject(name)

    private suspend fun objectMutation(
        method: HttpMethod,
        path: String,
        body: JsonElement,
    ): JsonObject =
        transport.mutate(method, path, body) as? JsonObject ?: throw GitHubApiException(invalidContract = true, outcomeUncertain = true)

    private fun path(repository: RepositoryIntegration): String {
        require(repository.owner.matches(Regex("[A-Za-z0-9_.-]+")) && repository.name.matches(Regex("[A-Za-z0-9_.-]+")))
        return "/repos/${repository.owner}/${repository.name}"
    }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
