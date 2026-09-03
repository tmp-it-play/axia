package team.themoment.axia.integration.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.products.RepositoryIntegration
import team.themoment.axia.tasks.TaskKind
import java.time.Clock
import java.time.Instant

class GitHubWorkClient(
    val transport: GitHubAppTransport,
    private val organization: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(organization.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,98}")))
    }

    suspend fun installationRepositories(): List<JsonObject> =
        transport
            .list("/installation/repositories", collectionKey = "repositories")
            .filter { it.requiredObject("owner").requiredText("login").equals(organization, ignoreCase = true) }

    suspend fun userById(numericId: Long): GitHubUserIdentity? {
        require(numericId > 0)
        val user =
            try {
                transport.readObject("/user/$numericId")
            } catch (failure: GitHubApiException) {
                if (failure.status == 404) return null else throw failure
            }
        if (user.requiredLong("id") != numericId || user.requiredText("type") != "User") throw GitHubApiException(invalidContract = true)
        return GitHubUserIdentity(numericId, user.requiredText("login"), user.text("node_id"), "User")
    }

    suspend fun repository(name: String): JsonObject {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val installed =
            installationRepositories().singleOrNull { it.requiredText("name").equals(name, ignoreCase = true) }
                ?: throw GitHubApiException(status = 404)
        if (installed.boolean("archived") || installed.boolean("fork") || installed.boolean("is_template")) {
            throw GitHubApiException(status = 422)
        }
        return installed
    }

    suspend fun repositoryTasks(
        repository: RepositoryIntegration,
        since: Instant?,
        initialImport: Boolean,
    ): List<GitHubTaskSnapshot> {
        val parameters =
            buildMap {
                put("state", if (initialImport) "open" else "all")
                put("sort", "updated")
                put("direction", "asc")
                since?.let { put("since", it.toString()) }
            }
        return transport.list(repository.path() + "/issues", parameters).map { issue ->
            task(repository, issue.requiredLong("number").toInt())
        }
    }

    suspend fun task(
        repository: RepositoryIntegration,
        number: Int,
    ): GitHubTaskSnapshot {
        require(number > 0)
        val requestedAt = clock.instant()
        val base = repository.path()
        val issue = transport.readObject("$base/issues/$number")
        val isPullRequest = issue.objectOrNull("pull_request") != null
        val pull = if (isPullRequest) transport.readObject("$base/pulls/$number") else null
        val timeline = transport.list("$base/issues/$number/timeline")
        val comments = transport.list("$base/issues/$number/comments")
        val assignees = issue.objects("assignees").map(JsonObject::restActor)
        val completions = completionOccurrences(issue, pull, timeline, assignees)
        val reviews = if (pull != null) reviewAssignments(pull) else emptyList()
        val convertedAt = if (pull == null) conversionTime(issue.requiredText("node_id")) else null
        val relationships = relationships(issue.requiredText("node_id"), isPullRequest)
        val details =
            buildJsonObject {
                put("labels", JsonArray(issue.objects("labels").map { JsonPrimitive(it.requiredText("name")) }))
                put("milestone", issue["milestone"] ?: JsonNull)
                put("comments", JsonArray(comments))
                // Stable occurrence IDs catch close/reopen between polls without comparing second-resolution timestamps.
                put(
                    "closedOccurrenceIds",
                    JsonArray(
                        timeline
                            .filter { it.text("event") in setOf("closed", "merged") }
                            .map { it.requiredText("node_id") }
                            .distinct()
                            .sorted()
                            .map(::JsonPrimitive),
                    ),
                )
                relationships.forEach { (key, value) -> put(key, value) }
            }
        // Reads of separate endpoints are not a transaction. Reject an observed mid-fetch change;
        // no partial timeline may freeze credits against a different assignee snapshot.
        val confirmedIssue = transport.readObject("$base/issues/$number")
        if (ISSUE_SNAPSHOT_KEYS.any { issue[it] != confirmedIssue[it] }) throw GitHubApiException(invalidContract = true)
        if (pull != null) {
            val confirmedPull = transport.readObject("$base/pulls/$number")
            if (PULL_SNAPSHOT_KEYS.any { pull[it] != confirmedPull[it] }) throw GitHubApiException(invalidContract = true)
        }
        return GitHubTaskSnapshot(
            nodeId = issue.requiredText("node_id"),
            kind = if (isPullRequest) TaskKind.PULL_REQUEST else TaskKind.ISSUE,
            number = number,
            title = issue.requiredText("title"),
            body = issue.text("body") ?: "",
            url = issue.requiredText("html_url"),
            open = issue.requiredText("state") == "open",
            completed = if (pull != null) pull.boolean("merged") else issue.requiredText("state") == "closed",
            createdAt = issue.requiredInstant("created_at"),
            updatedAt = issue.requiredInstant("updated_at"),
            closedAt = issue.instant("closed_at"),
            assignees = assignees,
            reviewAssignments = reviews,
            completions = completions,
            details = details,
            snapshotRequestedAt = requestedAt,
            convertedAt = convertedAt,
            assignmentOccurrences =
                timeline.filter { it.text("event") in setOf("assigned", "unassigned") }.map {
                    GitHubAssignmentOccurrence(
                        it.requiredText("node_id"),
                        it.requiredObject("assignee").restActor(),
                        it.requiredText("event") == "assigned",
                        it.requiredInstant("created_at"),
                    )
                },
        )
    }

    suspend fun project(number: Int): GitHubProjectSnapshot {
        require(number > 0 && number != 19)
        val fields = mutableListOf<JsonObject>()
        var after: String? = null
        var project: JsonObject
        do {
            project =
                transport
                    .graphQL(
                        PROJECT_QUERY,
                        buildJsonObject {
                            put("organization", organization)
                            put("number", number)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                        resourceScoped = true,
                        validate = { data ->
                            val board = data.requiredObject("organization").boardObject("projectV2")
                            if (board.requiredText("id").isBlank()) throw GitHubApiException(invalidContract = true)
                            board.requiredText("title")
                            board.requiredText("url")
                            val connection = board.requiredObject("fields")
                            connection.validateConnection()
                            connection.objects("nodes").forEach { it.validateProjectField() }
                        },
                    ).requiredObject("organization")
                    .boardObject("projectV2")
            val connection = project.requiredObject("fields")
            fields += connection.objects("nodes")
            after = connection.nextCursor()
        } while (after != null)
        val repositories = mutableSetOf<String>()
        after = null
        do {
            val connection =
                transport
                    .graphQL(
                        PROJECT_REPOSITORIES_QUERY,
                        buildJsonObject {
                            put("id", project.requiredText("id"))
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                        resourceScoped = true,
                        validate = { it.boardObject("node").requiredObject("repositories").validateConnection() },
                    ).boardObject("node")
                    .requiredObject("repositories")
            repositories += connection.objects("nodes").map { it.requiredText("id") }
            after = connection.nextCursor()
        } while (after != null)
        return GitHubProjectSnapshot(
            project.requiredText("id"),
            number,
            project.requiredText("title"),
            fields,
            repositories,
            project.requiredText("url"),
        )
    }

    suspend fun projectItems(nodeId: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var after: String? = null
        do {
            val connection =
                transport
                    .graphQL(
                        PROJECT_ITEMS_QUERY,
                        buildJsonObject {
                            put("id", nodeId)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                        resourceScoped = true,
                        validate = { data ->
                            val connection = data.boardObject("node").requiredObject("items")
                            connection.validateConnection()
                            connection.objects("nodes").forEach { item ->
                                item.requiredObject("fieldValues").validateFieldValues()
                                item
                                    .objectOrNull("content")
                                    ?.takeIf { it.text("__typename") == "DraftIssue" }
                                    ?.requiredObject("assignees")
                                    ?.validateConnection()
                            }
                        },
                    ).boardObject("node")
                    .requiredObject("items")
            items += connection.objects("nodes")
            after = connection.nextCursor()
        } while (after != null)
        // Nested connections have their own cursors; never silently truncate a board's field values.
        return items.map { item ->
            val firstValues = item.requiredObject("fieldValues")
            val values = firstValues.objects("nodes").toMutableList()
            var fieldCursor = firstValues.nextCursor()
            while (fieldCursor != null) {
                val connection =
                    transport
                        .graphQL(
                            ITEM_FIELDS_QUERY,
                            buildJsonObject {
                                put("id", item.requiredText("id"))
                                put("after", fieldCursor)
                            },
                            // A disappearing item is an incomplete board read, not loss of the board itself.
                            validate = { it.requiredObject("node").requiredObject("fieldValues").validateFieldValues() },
                        ).requiredObject("node")
                        .requiredObject("fieldValues")
                values += connection.objects("nodes")
                fieldCursor = connection.nextCursor()
            }
            var content = item.objectOrNull("content")
            if (content?.text("__typename") == "DraftIssue") {
                val assignees = content.requiredObject("assignees").objects("nodes").toMutableList()
                var assigneeCursor = content.requiredObject("assignees").nextCursor()
                while (assigneeCursor != null) {
                    val connection =
                        transport
                            .graphQL(
                                DRAFT_ASSIGNEES_QUERY,
                                buildJsonObject {
                                    put("id", content.requiredText("id"))
                                    put("after", assigneeCursor)
                                },
                                validate = { it.requiredObject("node").requiredObject("assignees").validateConnection() },
                            ).requiredObject("node")
                            .requiredObject("assignees")
                    assignees += connection.objects("nodes")
                    assigneeCursor = connection.nextCursor()
                }
                content = JsonObject(content + ("allAssignees" to JsonArray(assignees)))
            }
            JsonObject(item + ("allFieldValues" to JsonArray(values)) + ("content" to (content ?: JsonNull)))
        }
    }

    private suspend fun conversionTime(nodeId: String): Instant? {
        val events = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val connection =
                transport
                    .graphQL(
                        "query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on Issue { timelineItems(first:100,after:${'$'}after,itemTypes:[CONVERTED_FROM_DRAFT_EVENT]) { nodes { ... on ConvertedFromDraftEvent { id createdAt } } pageInfo { hasNextPage endCursor } } } } }",
                        buildJsonObject {
                            put("id", nodeId)
                            put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    ).requiredObject("node")
                    .requiredObject("timelineItems")
            events += connection.objects("nodes")
            cursor = connection.nextCursor()
        } while (cursor != null)
        if (events.size > 1) throw GitHubApiException(invalidContract = true)
        return events.singleOrNull()?.requiredInstant("createdAt")
    }

    private suspend fun relationships(
        nodeId: String,
        pullRequest: Boolean,
    ): JsonObject {
        suspend fun connection(
            type: String,
            field: String,
            extraArguments: String = "",
        ): List<JsonObject> {
            val nodes = mutableListOf<JsonObject>()
            var cursor: String? = null
            do {
                val result =
                    transport
                        .graphQL(
                            "query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on $type { $field(first:100,after:${'$'}after$extraArguments) { nodes { id title url } pageInfo { hasNextPage endCursor } } } } }",
                            buildJsonObject {
                                put("id", nodeId)
                                put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
                            },
                        ).requiredObject("node")
                        .requiredObject(field)
                nodes += result.objects("nodes")
                cursor = result.nextCursor()
            } while (cursor != null)
            return nodes.distinctBy { it.requiredText("id") }
        }
        return if (pullRequest) {
            buildJsonObject {
                put("linkedIssues", JsonArray(connection("PullRequest", "closingIssuesReferences")))
                put("linkedPullRequests", JsonArray(emptyList()))
                put("subIssues", JsonArray(emptyList()))
                put("parent", JsonNull)
            }
        } else {
            val parent =
                transport
                    .graphQL(
                        "query(${'$'}id:ID!) { node(id:${'$'}id) { ... on Issue { parent { id title url } } } }",
                        buildJsonObject { put("id", nodeId) },
                    ).requiredObject("node")["parent"] ?: throw GitHubApiException(invalidContract = true)
            buildJsonObject {
                put("linkedIssues", JsonArray(emptyList()))
                put("linkedPullRequests", JsonArray(connection("Issue", "closedByPullRequestsReferences", ",includeClosedPrs:true")))
                put("subIssues", JsonArray(connection("Issue", "subIssues")))
                put("parent", parent)
            }
        }
    }

    private suspend fun reviewAssignments(pull: JsonObject): List<GitHubReviewAssignment> {
        val pending = pull.objects("requested_reviewers").map(JsonObject::restActor).associateBy { it.id }
        if (pending.isEmpty()) return emptyList()
        val latest = mutableMapOf<Long, GitHubReviewAssignment>()
        var cursor: String? = null
        do {
            val connection =
                transport
                    .graphQL(
                        REVIEW_TIMELINE_QUERY,
                        buildJsonObject {
                            put("id", pull.requiredText("node_id"))
                            put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    ).requiredObject("node")
                    .requiredObject("timelineItems")
            connection.objects("nodes").forEach { event ->
                val reviewer = event.objectOrNull("requestedReviewer") ?: return@forEach
                val id = reviewer.long("databaseId") ?: return@forEach // Team review requests do not identify one Axia assignee.
                pending[id]?.let { actor ->
                    latest[id] = GitHubReviewAssignment(actor, event.requiredText("id"), event.requiredInstant("createdAt"))
                }
            }
            cursor = connection.nextCursor()
        } while (cursor != null)
        if (latest.keys != pending.keys) throw GitHubApiException(invalidContract = true)
        return latest.values.toList()
    }

    companion object {
        private val ISSUE_SNAPSHOT_KEYS =
            listOf("node_id", "updated_at", "state", "closed_at", "assignees", "title", "body", "labels", "milestone")
        private val PULL_SNAPSHOT_KEYS = listOf("node_id", "updated_at", "state", "merged_at", "requested_reviewers")

        /** Walk backwards from today's assignees to recover the assignees at each immutable occurrence. */
        fun completionOccurrences(
            issue: JsonObject,
            pull: JsonObject?,
            timeline: List<JsonObject>,
            currentAssignees: List<GitHubActor>,
        ): List<GitHubCompletionOccurrence> {
            val atTime = currentAssignees.associateBy { it.id }.toMutableMap()
            val result = mutableListOf<GitHubCompletionOccurrence>()
            val mergedAt = pull?.instant("merged_at")
            var mergeCaptured = false
            timeline.asReversed().forEach { event ->
                when (event.text("event")) {
                    "assigned" -> {
                        event.objectOrNull("assignee")?.restActor()?.let { atTime.remove(it.id) }
                    }

                    "unassigned" -> {
                        event.objectOrNull("assignee")?.restActor()?.let { atTime[it.id] = it }
                    }

                    "closed" -> {
                        if (pull == null) {
                            result +=
                                GitHubCompletionOccurrence(
                                    event.requiredText("node_id"),
                                    CompletionKind.ISSUE_CLOSE,
                                    event.requiredInstant("created_at"),
                                    atTime.values.toList(),
                                )
                        }
                    }

                    "merged" -> {
                        if (mergedAt != null) {
                            if (mergeCaptured ||
                                event.requiredInstant("created_at") != mergedAt
                            ) {
                                throw GitHubApiException(invalidContract = true)
                            }
                            result +=
                                GitHubCompletionOccurrence(
                                    event.requiredText("node_id"),
                                    CompletionKind.PR_MERGE,
                                    mergedAt,
                                    atTime.values.toList(),
                                )
                            mergeCaptured = true
                        }
                    }
                }
            }
            if (mergedAt != null && !mergeCaptured) {
                // A timestamp alone cannot order same-second assignment events. Retry a complete timeline.
                throw GitHubApiException(invalidContract = true)
            }
            return result.sortedBy { it.occurredAt }
        }

        private val PROJECT_QUERY =
            """
            query(${'$'}organization:String!, ${'$'}number:Int!, ${'$'}after:String) {
              organization(login:${'$'}organization) { projectV2(number:${'$'}number) {
                id title url fields(first:100,after:${'$'}after) {
                  nodes { ... on ProjectV2Field { id name dataType }
                    ... on ProjectV2SingleSelectField { id name dataType options { id name } }
                    ... on ProjectV2IterationField { id name dataType } }
                  pageInfo { hasNextPage endCursor }
                }
              } }
            }
            """.trimIndent()

        private val PROJECT_REPOSITORIES_QUERY =
            """
            query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on ProjectV2 {
              repositories(first:100,after:${'$'}after) { nodes { id } pageInfo { hasNextPage endCursor } }
            } } }
            """.trimIndent()

        private val FIELD_VALUE_FRAGMENT =
            """
            nodes {
              ... on ProjectV2ItemFieldSingleSelectValue { optionId field { ... on ProjectV2SingleSelectField { id } } }
              ... on ProjectV2ItemFieldDateValue { date field { ... on ProjectV2Field { id } } }
            } pageInfo { hasNextPage endCursor }
            """.trimIndent()

        private val PROJECT_ITEMS_QUERY =
            """
            query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on ProjectV2 {
              items(first:100,after:${'$'}after,archivedStates:[ARCHIVED,NOT_ARCHIVED]) { nodes {
                id isArchived createdAt updatedAt
                content {
                  __typename
                  ... on Issue { id number repository { id name } }
                  ... on PullRequest { id number repository { id name } }
                  ... on DraftIssue { id title body createdAt updatedAt
                    assignees(first:100) { nodes { id databaseId login } pageInfo { hasNextPage endCursor } }
                  }
                }
                fieldValues(first:100) { $FIELD_VALUE_FRAGMENT }
              } pageInfo { hasNextPage endCursor } }
            } } }
            """.trimIndent()

        private val ITEM_FIELDS_QUERY =
            """
            query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on ProjectV2Item {
              fieldValues(first:100,after:${'$'}after) { $FIELD_VALUE_FRAGMENT }
            } } }
            """.trimIndent()

        private val DRAFT_ASSIGNEES_QUERY =
            """
            query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on DraftIssue {
              assignees(first:100,after:${'$'}after) { nodes { id databaseId login } pageInfo { hasNextPage endCursor } }
            } } }
            """.trimIndent()

        private val REVIEW_TIMELINE_QUERY =
            """
            query(${'$'}id:ID!,${'$'}after:String) { node(id:${'$'}id) { ... on PullRequest {
              timelineItems(first:100,after:${'$'}after,itemTypes:[REVIEW_REQUESTED_EVENT]) {
                nodes { ... on ReviewRequestedEvent { id createdAt requestedReviewer {
                  ... on User { id databaseId login }
                } } } pageInfo { hasNextPage endCursor }
              }
            } } }
            """.trimIndent()
    }
}

internal fun RepositoryIntegration.path(): String {
    require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,98}")) && name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
    return "/repos/$owner/$name"
}

internal fun JsonObject.nextCursor(): String? {
    val pageInfo = requiredObject("pageInfo")
    return if (pageInfo.boolean("hasNextPage")) {
        pageInfo.requiredText("endCursor").takeIf { it.isNotBlank() } ?: throw GitHubApiException(invalidContract = true)
    } else {
        null
    }
}

/** Only an explicit null for a queried board proves absence; missing keys/wrong types are malformed responses. */
private fun JsonObject.boardObject(key: String): JsonObject =
    when (val value = get(key)) {
        JsonNull -> throw GitHubApiException(status = 404)
        is JsonObject -> value
        else -> throw GitHubApiException(invalidContract = true)
    }

private fun JsonObject.validateConnection() {
    objects("nodes")
    nextCursor()
}

private fun JsonObject.validateFieldValues() {
    validateConnection()
    objects("nodes").forEach { it.validateProjectFieldValue() }
}
