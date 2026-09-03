package team.themoment.axia.tasks

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.authorization.accessDenied
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.domain.ProductId
import team.themoment.axia.integration.github.GitHubActor
import team.themoment.axia.integration.github.GitHubApiException
import team.themoment.axia.integration.github.GitHubMutationClient
import team.themoment.axia.integration.github.GitHubSyncService
import team.themoment.axia.integration.github.GitHubWorkClient
import team.themoment.axia.integration.github.ProjectBoardAdapter
import team.themoment.axia.integration.github.requiredLong
import team.themoment.axia.integration.github.requiredObject
import team.themoment.axia.integration.github.requiredText
import team.themoment.axia.members.memberTransaction
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.query
import team.themoment.axia.products.IntegrationAccess
import team.themoment.axia.products.IntegrationStore
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.products.RepositoryIntegration
import team.themoment.axia.scheduling.ProviderHealthStore
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException

class TaskMutationService(
    private val dataSource: DataSource,
    private val authorization: AuthorizationService,
    private val integrations: IntegrationStore,
    private val tasks: TaskStore,
    private val github: GitHubWorkClient,
    private val mutations: GitHubMutationClient,
    private val adapter: ProjectBoardAdapter,
    private val sync: GitHubSyncService,
    private val health: ProviderHealthStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val operations = ExternalWriteOperationStore(dataSource, clock)

    suspend fun createIssue(
        userId: Long,
        request: CreateIssueRequest,
    ): MutationResult {
        validateText(request.title, request.body)
        validateLabels(request.labels, request.milestoneNumber)
        validateIds(request.assigneeUserIds)
        val repository = enabledRepository(request.repositoryId)
        val project =
            request.projectId?.let {
                enabledProject(it).also { target ->
                    if (target.productId !=
                        repository.productId
                    ) {
                        throw invalidMutation()
                    }
                }
            }
        val scope = MutationScope(product = repository.productId, repositoryId = repository.id, projectId = project?.id)
        return execute(
            userId,
            scope,
            ExternalWriteAttempt(
                request.operationId,
                userId,
                null,
                "CREATE_ISSUE",
                request.operationIssuedAtEpochMillis,
                Json.encodeToJsonElement(request),
            ),
        ) { _, retry ->
            val actors = if (retry) emptyMap() else candidateActors(repository.productId, request.assigneeUserIds)
            MutationPlan(
                dispatch = { context ->
                    context.candidates.putAll(actors.mapValues { it.value.id })
                    context.record("addedGitHubIds", JsonArray(actors.values.map { JsonPrimitive(it.id) }))
                    val result =
                        context.write {
                            mutations.createIssue(
                                repository,
                                request.title.trim(),
                                request.body,
                                actors.values.toList(),
                                request.labels,
                                request.milestoneNumber,
                            )
                        }
                    context.record("createdNumber", JsonPrimitive(result.requiredLong("number")))
                    context.record("createdNodeId", JsonPrimitive(result.requiredText("node_id")))
                    if (project !=
                        null
                    ) {
                        context.write { mutations.addProject(project, result.requiredText("node_id"), request.operationId) }
                    }
                },
                confirm = { checkpoint ->
                    val number = checkpoint.number("createdNumber")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                    val node = checkpoint.textValue("createdNodeId")
                    if (number == null || node == null) {
                        mutations.inspectUncertainCreation(repository, project)
                        Confirmation(false, null)
                    } else {
                        val remote = github.task(repository, number)
                        if (remote.nodeId != node) throw GitHubApiException(invalidContract = true)
                        val id = tasks.ingestRepository(repository.id, remote)
                        if (project != null) refreshBoard(project)
                        val attached = project == null || (id != null && projectContext(id, project.id) != null)
                        val fieldsMatch =
                            id != null &&
                                matchesIssue(
                                    LoadedTask(tasks.raw(id), ""),
                                    request.title.trim(),
                                    request.body,
                                    request.labels,
                                    request.milestoneNumber,
                                )
                        Confirmation(
                            fieldsMatch && attached &&
                                remote.assignees
                                    .map { it.id }
                                    .toSet()
                                    .containsAll(checkpoint.ids("addedGitHubIds")),
                            id,
                        )
                    }
                },
            )
        }
    }

    suspend fun createDraft(
        userId: Long,
        projectId: Long,
        request: CreateDraftRequest,
    ): MutationResult {
        validateText(request.title, request.body)
        validateIds(request.assigneeUserIds)
        val project = enabledProject(projectId)
        val scope = MutationScope(product = project.productId, projectId = projectId)
        return execute(
            userId,
            scope,
            ExternalWriteAttempt(
                request.operationId,
                userId,
                null,
                "CREATE_DRAFT:$projectId",
                request.operationIssuedAtEpochMillis,
                Json.encodeToJsonElement(request),
            ),
        ) { _, retry ->
            val actors = if (retry) emptyMap() else candidateActors(project.productId, request.assigneeUserIds)
            MutationPlan(
                dispatch = { context ->
                    context.candidates.putAll(actors.mapValues { it.value.id })
                    context.record("addedGitHubIds", JsonArray(actors.values.map { JsonPrimitive(it.id) }))
                    val item =
                        context.write {
                            mutations.createDraft(
                                project,
                                request.title.trim(),
                                request.body,
                                actors.values.toList(),
                                request.operationId,
                            )
                        }
                    context.record("createdNodeId", JsonPrimitive(item.requiredObject("content").requiredText("id")))
                    context.record("projectItemId", JsonPrimitive(item.requiredText("id")))
                },
                confirm = { checkpoint ->
                    refreshBoard(project)
                    val node = checkpoint.textValue("createdNodeId")
                    val id = node?.let { tasks.byCanonicalId(it) }
                    if (id == null) {
                        mutations.inspectUncertainCreation(null, project)
                        Confirmation(false, null)
                    } else {
                        val raw = tasks.raw(id)
                        Confirmation(
                            raw.kind == TaskKind.DRAFT && raw.title == request.title.trim() && raw.body == request.body &&
                                assignees(id).map { it.id }.toSet().containsAll(checkpoint.ids("addedGitHubIds")),
                            id,
                        )
                    }
                },
            )
        }
    }

    suspend fun assign(
        userId: Long,
        taskId: Long,
        request: AssignTaskRequest,
    ): MutationResult {
        validateIds(request.addUserIds)
        validateIds(request.removeGitHubIds)
        return existing(
            userId,
            taskId,
            "ASSIGNEES",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            request.expectedUpdatedAtEpochMillis,
            setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST, TaskKind.DRAFT),
        ) { before, state ->
            val current = assignees(taskId)
            val additions = if (state.status == "PREPARED") candidateActors(before.raw.productId, request.addUserIds) else emptyMap()
            if (additions.values.any { it.id in request.removeGitHubIds }) throw invalidMutation()
            val addedIds = if (additions.isNotEmpty()) additions.values.map { it.id }.toSet() else state.checkpoint.ids("addedGitHubIds")
            val retained =
                current
                    .filter {
                        it.id !in request.removeGitHubIds
                    }.associateBy { it.id }
                    .toMutableMap()
                    .apply { additions.values.forEach { put(it.id, it) } }
                    .values
                    .toList()
            if (additions.isNotEmpty() && retained.size > 10) throw invalidMutation()
            MutationPlan(
                dispatch = { context ->
                    context.candidates.putAll(additions.mapValues { it.value.id })
                    context.record("addedGitHubIds", JsonArray(addedIds.sorted().map(::JsonPrimitive)))
                    if (before.raw.kind == TaskKind.DRAFT) {
                        context.write { mutations.updateDraft(before.raw.nodeId, null, null, retained, request.operationId) }
                    } else {
                        val removals = current.filter { it.id in request.removeGitHubIds }
                        if (removals.isNotEmpty()) {
                            context.write {
                                mutations.changeAssignees(
                                    before.raw.nodeId,
                                    removals,
                                    false,
                                    request.operationId,
                                )
                            }
                        }
                        val new = additions.values.filter { actor -> current.none { it.id == actor.id } }
                        if (new.isNotEmpty()) context.write { mutations.changeAssignees(before.raw.nodeId, new, true, request.operationId) }
                    }
                },
                confirm = { checkpoint ->
                    refresh(taskId)
                    val actual = assignees(taskId).map { it.id }.toSet()
                    val desired = checkpoint.ids("addedGitHubIds")
                    Confirmation(actual.containsAll(desired) && actual.intersect(request.removeGitHubIds).isEmpty(), taskId)
                },
            )
        }
    }

    suspend fun reviewers(
        userId: Long,
        taskId: Long,
        request: ReviewRequestUpdate,
    ): MutationResult {
        validateIds(request.addUserIds)
        validateIds(request.removeGitHubIds)
        return existing(
            userId,
            taskId,
            "REVIEWERS",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            request.expectedUpdatedAtEpochMillis,
            setOf(TaskKind.PULL_REQUEST),
        ) { before, state ->
            val repository = requireRepository(before.raw)
            val current = reviewers(taskId)
            val additions = if (state.status == "PREPARED") candidateActors(before.raw.productId, request.addUserIds) else emptyMap()
            if (additions.values.any { it.id in request.removeGitHubIds }) throw invalidMutation()
            MutationPlan(
                dispatch = { context ->
                    context.candidates.putAll(additions.mapValues { it.value.id })
                    context.record("addedGitHubIds", JsonArray(additions.values.map { JsonPrimitive(it.id) }))
                    context.record("previousReviews", JsonObject(current.associate { it.first.id.toString() to JsonPrimitive(it.second) }))
                    val remove =
                        current.map { it.first }.filter {
                            it.id in request.removeGitHubIds ||
                                (request.reRequest && additions.values.any { added -> added.id == it.id })
                        }
                    if (remove.isNotEmpty()) {
                        context.write {
                            mutations.reviewers(
                                repository,
                                requireNotNull(before.raw.number),
                                remove,
                                false,
                            )
                        }
                    }
                    val add = additions.values.filter { request.reRequest || current.none { review -> review.first.id == it.id } }
                    if (add.isNotEmpty()) context.write { mutations.reviewers(repository, requireNotNull(before.raw.number), add, true) }
                },
                confirm = { checkpoint ->
                    refresh(taskId)
                    val actual = reviewers(taskId).associate { it.first.id to it.second }
                    val desired = checkpoint.ids("addedGitHubIds")
                    val previous = checkpoint["previousReviews"] as? JsonObject
                    val fresh = !request.reRequest || desired.all { id -> actual[id] != previous?.textValue(id.toString()) }
                    Confirmation(
                        actual.keys.containsAll(desired) && actual.keys.intersect(request.removeGitHubIds).isEmpty() && fresh,
                        taskId,
                    )
                },
            )
        }
    }

    suspend fun updateIssue(
        userId: Long,
        taskId: Long,
        request: UpdateIssueRequest,
    ): MutationResult {
        validateText(request.title, request.body)
        validateLabels(request.labels, request.milestoneNumber)
        return existing(
            userId,
            taskId,
            "UPDATE_ISSUE",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            request.expectedUpdatedAtEpochMillis,
            setOf(TaskKind.ISSUE),
        ) { before, _ ->
            val repository = requireRepository(before.raw)
            MutationPlan(
                dispatch = { context ->
                    context.write {
                        mutations.updateIssue(
                            repository,
                            requireNotNull(before.raw.number),
                            request.title.trim(),
                            request.body,
                            request.labels,
                            request.milestoneNumber,
                        )
                    }
                },
                confirm = { _ ->
                    val latest = refresh(taskId)
                    Confirmation(matchesIssue(latest, request.title.trim(), request.body, request.labels, request.milestoneNumber), taskId)
                },
            )
        }
    }

    suspend fun updatePullRequest(
        userId: Long,
        taskId: Long,
        request: UpdatePullRequestRequest,
    ): MutationResult {
        validateLabels(request.labels, request.milestoneNumber)
        return existing(
            userId,
            taskId,
            "UPDATE_PULL_REQUEST",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            null,
            setOf(TaskKind.PULL_REQUEST),
        ) { before, _ ->
            val repository = requireRepository(before.raw)
            MutationPlan(
                dispatch = { context ->
                    context.write {
                        mutations.updateIssue(
                            repository,
                            requireNotNull(before.raw.number),
                            null,
                            null,
                            request.labels,
                            request.milestoneNumber,
                        )
                    }
                },
                confirm = { _ ->
                    val latest = refresh(taskId)
                    Confirmation(matchesIssue(latest, null, null, request.labels, request.milestoneNumber), taskId)
                },
            )
        }
    }

    suspend fun issueState(
        userId: Long,
        taskId: Long,
        request: TaskMutationRequest,
        open: Boolean,
    ): MutationResult =
        existing(
            userId,
            taskId,
            if (open) "REOPEN_ISSUE" else "CLOSE_ISSUE",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            request.expectedUpdatedAtEpochMillis,
            setOf(TaskKind.ISSUE),
        ) { before, _ ->
            val repository = requireRepository(before.raw)
            MutationPlan(
                dispatch = { context -> context.write { mutations.issueState(repository, requireNotNull(before.raw.number), open) } },
                confirm = { _ -> Confirmation(refresh(taskId).raw.open == open, taskId) },
            )
        }

    suspend fun comment(
        userId: Long,
        taskId: Long,
        request: CreateCommentRequest,
        expectedKind: TaskKind,
    ): MutationResult {
        if (request.body.isBlank() || request.body.length > MAX_BODY || '\u0000' in request.body) throw invalidMutation()
        return existing(
            userId,
            taskId,
            "COMMENT_${expectedKind.name}",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            null,
            setOf(expectedKind),
        ) { before, _ ->
            val repository = requireRepository(before.raw)
            MutationPlan(
                dispatch = { context ->
                    val result = context.write { mutations.comment(repository, requireNotNull(before.raw.number), request.body) }
                    context.record("commentId", JsonPrimitive(result.requiredLong("id")))
                },
                confirm = { checkpoint ->
                    refresh(taskId)
                    val id = checkpoint.number("commentId")
                    val confirmed = id != null && mutations.readComment(repository, id).textValue("body") == request.body
                    Confirmation(confirmed, taskId)
                },
            )
        }
    }

    suspend fun updateDraft(
        userId: Long,
        taskId: Long,
        request: UpdateDraftRequest,
    ): MutationResult {
        validateText(request.title, request.body)
        return existing(
            userId,
            taskId,
            "UPDATE_DRAFT",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            null,
            setOf(TaskKind.DRAFT),
        ) { before, _ ->
            MutationPlan(
                dispatch = { context ->
                    context.write {
                        mutations.updateDraft(
                            before.raw.nodeId,
                            request.title.trim(),
                            request.body,
                            null,
                            request.operationId,
                        )
                    }
                },
                confirm = { _ ->
                    val latest = refresh(taskId)
                    Confirmation(latest.raw.title == request.title.trim() && latest.raw.body == request.body, taskId)
                },
            )
        }
    }

    suspend fun projectFields(
        userId: Long,
        taskId: Long,
        request: ProjectFieldsUpdate,
    ): MutationResult {
        listOfNotNull(request.startDate, request.targetDate).forEach { date ->
            if (!date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) ||
                runCatching { LocalDate.parse(date) }.isFailure
            ) {
                throw invalidMutation()
            }
        }
        return existing(
            userId,
            taskId,
            "PROJECT_FIELDS",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            request.expectedUpdatedAtEpochMillis,
            setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST, TaskKind.DRAFT),
            setOf(request.projectId),
        ) { _, _ ->
            val project = enabledProject(request.projectId)
            val item = projectContext(taskId, project.id) ?: throw invalidMutation()
            MutationPlan(
                dispatch = { context ->
                    // The adapter makes four independent writes. Any error can follow a successful field write.
                    context.write {
                        try {
                            adapter.updateFields(project, item.itemNodeId, request)
                        } catch (failure: GitHubApiException) {
                            throw GitHubApiException(status = failure.status, outcomeUncertain = true)
                        }
                    }
                },
                confirm = { _ ->
                    refresh(taskId, setOf(project.id))
                    val actual = projectContext(taskId, project.id)
                    Confirmation(
                        actual != null && actual.status == request.status && actual.priority == request.priority &&
                            actual.startDate == request.startDate &&
                            actual.targetDate == request.targetDate,
                        taskId,
                    )
                },
            )
        }
    }

    suspend fun addProject(
        userId: Long,
        taskId: Long,
        request: AddTaskToProjectRequest,
    ): MutationResult =
        existing(
            userId,
            taskId,
            "ADD_PROJECT",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            null,
            setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST),
            setOf(request.projectId),
        ) { before, _ ->
            val project = enabledProject(request.projectId)
            MutationPlan(
                dispatch = { context ->
                    if (projectContext(taskId, project.id) ==
                        null
                    ) {
                        context.write { mutations.addProject(project, before.raw.nodeId, request.operationId) }
                    }
                },
                confirm = { _ ->
                    refresh(taskId, setOf(project.id))
                    Confirmation(projectContext(taskId, project.id) != null, taskId)
                },
            )
        }

    suspend fun archive(
        userId: Long,
        taskId: Long,
        request: ProjectItemArchiveRequest,
    ): MutationResult =
        existing(
            userId,
            taskId,
            "PROJECT_ARCHIVE",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            null,
            setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST, TaskKind.DRAFT),
            setOf(request.projectId),
        ) { _, _ ->
            val project = enabledProject(request.projectId)
            val item = projectContext(taskId, project.id) ?: throw invalidMutation()
            MutationPlan(
                dispatch = { context ->
                    if (item.archived !=
                        request.archived
                    ) {
                        context.write { adapter.archive(project, item.itemNodeId, request.archived, request.operationId) }
                    }
                },
                confirm = { _ ->
                    refresh(taskId, setOf(project.id))
                    Confirmation(projectContext(taskId, project.id)?.archived == request.archived, taskId)
                },
            )
        }

    suspend fun convertDraft(
        userId: Long,
        taskId: Long,
        request: ConvertDraftRequest,
    ): MutationResult =
        existing(
            userId,
            taskId,
            "CONVERT_DRAFT",
            request.operationId,
            request.operationIssuedAtEpochMillis,
            Json.encodeToJsonElement(request),
            request.expectedRevision,
            null,
            setOf(TaskKind.DRAFT),
        ) { before, state ->
            val repository = enabledRepository(request.repositoryId)
            if (repository.productId != before.raw.productId) throw invalidMutation()
            val contexts = projectContexts(taskId)
            val projectId = state.checkpoint.number("sourceProjectId") ?: contexts.singleOrNull()?.projectId ?: throw invalidMutation()
            val itemId = state.checkpoint.textValue("sourceItemId") ?: contexts.singleOrNull()?.itemNodeId ?: throw invalidMutation()
            val project = enabledProject(projectId)
            if (project.productId != before.raw.productId) throw invalidMutation()
            MutationPlan(
                dispatch = { context ->
                    context.extraRepository = repository.id
                    context.extraProject = project.id
                    context.record("sourceProjectId", JsonPrimitive(project.id))
                    context.record("sourceItemId", JsonPrimitive(itemId))
                    context.write { mutations.convertDraft(itemId, repository, request.operationId) }
                },
                confirm = { checkpoint ->
                    refreshBoard(project)
                    val originalItem = checkpoint.textValue("sourceItemId")
                    val convertedId =
                        if (originalItem == null) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                dataSource.inTransaction(readOnly = true) {
                                    query(
                                        "SELECT t.id FROM task_projects p JOIN task_projections t ON t.id=p.task_id WHERE p.project_id=? AND p.item_node_id=? AND t.kind='ISSUE' AND t.repository_id=?",
                                        project.id,
                                        originalItem,
                                        repository.id,
                                    ) {
                                        it.getLong(1)
                                    }.singleOrNull()
                                }
                            }
                        }
                    // The immutable Project item proves conversion; completion eligibility uses GitHub's conversion event time.
                    Confirmation(convertedId != null && tasks.raw(convertedId).convertedAt != null, convertedId ?: taskId)
                },
            )
        }

    private suspend fun existing(
        userId: Long,
        taskId: Long,
        kind: String,
        operationId: String,
        issuedAt: Long,
        request: JsonElement,
        expectedRevision: String?,
        expectedUpdatedAt: Long?,
        kinds: Set<TaskKind>,
        additionalProjects: Set<Long> = emptySet(),
        plan: suspend (LoadedTask, ExternalWriteState) -> MutationPlan,
    ): MutationResult {
        requireRevision(expectedRevision)
        val scope = MutationScope(taskId = taskId, projectId = additionalProjects.singleOrNull())
        return execute(userId, scope, ExternalWriteAttempt(operationId, userId, taskId, kind, issuedAt, request)) { state, retry ->
            val cached = tasks.raw(taskId)
            if (cached.kind !in kinds) throw unsupportedMutation()
            val latest = refresh(taskId, additionalProjects)
            if (latest.raw.kind !in kinds) throw unsupportedMutation()
            if (!retry &&
                (
                    latest.revision != expectedRevision ||
                        (expectedUpdatedAt != null && latest.raw.updatedAt.toEpochMilli() != expectedUpdatedAt)
                )
            ) {
                throw staleSnapshot(userId, taskId)
            }
            val stale = staleSnapshot(userId, taskId)
            plan(latest, state).also { prepared ->
                prepared.preDispatch =
                    { connection -> if (tasks.revision(connection, taskId) != latest.revision) throw stale }
            }
        }
    }

    private suspend fun execute(
        userId: Long,
        scope: MutationScope,
        attempt: ExternalWriteAttempt,
        prepare: suspend (ExternalWriteState, Boolean) -> MutationPlan,
    ): MutationResult {
        health.requireWritable()
        val guard: (Connection) -> Unit = { authorize(it, userId, scope) }
        val claim = operations.claim(attempt, guard)
        if (claim.state.status == "CONFIRMED") return MutationResult(attempt.id, claim.state.taskId, true, true)
        if (claim.state.status == "FAILED") throw failedOperation(attempt.id)
        // A duplicate request must not confirm incomplete checkpoints while the first request is still dispatching.
        if (!claim.fresh &&
            claim.state.status in setOf("PREPARED", "IN_FLIGHT")
        ) {
            return MutationResult(attempt.id, claim.state.taskId, false, true)
        }
        var context: DispatchContext? = null
        try {
            val plan = prepare(claim.state, !claim.fresh)
            if (!claim.fresh) {
                val result = plan.confirm(claim.state.checkpoint)
                if (result.confirmed) operations.confirmed(attempt.id, result.taskId, claim.state.checkpoint)
                return MutationResult(attempt.id, result.taskId ?: claim.state.taskId, result.confirmed, true)
            }
            if (!operations.beginDispatch(attempt.id) { connection ->
                    guard(connection)
                    plan.preDispatch(connection)
                }
            ) {
                return MutationResult(attempt.id, claim.state.taskId, false, true)
            }
            context = DispatchContext(attempt, scope, claim.state.checkpoint)
            plan.dispatch(context)
            val result = plan.confirm(context.checkpoint)
            if (result.confirmed) {
                operations.confirmed(
                    attempt.id,
                    result.taskId,
                    context.checkpoint,
                )
            } else {
                operations.finishUnconfirmed(attempt.id, true)
            }
            return MutationResult(attempt.id, result.taskId ?: attempt.taskId, result.confirmed, true)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { if (claim.fresh) operations.finishUnconfirmed(attempt.id, context?.invoked == true) }
            throw cancelled
        } catch (failure: Exception) {
            val uncertain =
                context?.completedSteps?.let { it > 0 } == true ||
                    (context?.invoked == true && (failure !is GitHubApiException || failure.outcomeUncertain))
            if (claim.fresh) operations.finishUnconfirmed(attempt.id, uncertain)
            if (!claim.fresh || uncertain) {
                // Best-effort read-only confirmation; never invoke any missing or uncertain mutation step.
                val result =
                    try {
                        prepare(
                            ExternalWriteState("UNKNOWN", attempt.taskId, context?.checkpoint ?: claim.state.checkpoint),
                            true,
                        ).confirm(context?.checkpoint ?: claim.state.checkpoint)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                if (result?.confirmed ==
                    true
                ) {
                    operations.confirmed(attempt.id, result.taskId, context?.checkpoint ?: claim.state.checkpoint)
                }
                return MutationResult(attempt.id, result?.taskId ?: attempt.taskId, result?.confirmed == true, true)
            }
            if (failure is ApiFailure) throw failure
            if (failure is GitHubApiException) {
                throw ApiFailure(
                    HttpStatusCode.BadGateway,
                    ApiErrorCode.ExternalServiceUnavailable,
                    "GitHub 변경을 완료하지 못했습니다. 최신 상태를 확인해 주세요.",
                )
            }
            throw failure
        }
    }

    private inner class DispatchContext(
        val attempt: ExternalWriteAttempt,
        val scope: MutationScope,
        initial: JsonObject,
    ) {
        var checkpoint: JsonObject = initial
            private set
        var invoked = false
            private set
        var completedSteps = 0
            private set
        val candidates = linkedMapOf<Long, Long>()
        var extraRepository: Long? = null
        var extraProject: Long? = null

        suspend fun <T> write(action: suspend () -> T): T {
            withContext(Dispatchers.IO) {
                dataSource.memberTransaction {
                    ExternalWriteOperationStore.validateAttemptTime(attempt.id, attempt.issuedAtEpochMillis, clock.instant())
                    authorize(
                        this,
                        attempt.userId,
                        scope.copy(
                            repositoryId = extraRepository ?: scope.repositoryId,
                            projectId =
                                extraProject ?: scope.projectId,
                        ),
                    )
                    if (candidates.isNotEmpty()) {
                        validateCandidates(
                            this,
                            requireNotNull(scope.product ?: taskProduct(this, requireNotNull(scope.taskId))),
                            candidates.keys,
                            candidates,
                        )
                    }
                }
            }
            invoked = true
            return action().also { completedSteps++ }
        }

        suspend fun record(
            key: String,
            value: JsonElement,
        ) {
            checkpoint = JsonObject(checkpoint + (key to value))
            operations.checkpoint(attempt.id, checkpoint)
        }
    }

    private suspend fun refresh(
        taskId: Long,
        additionalProjects: Set<Long> = emptySet(),
    ): LoadedTask {
        val before = tasks.raw(taskId)
        if (before.kind != TaskKind.DRAFT) {
            val repository = requireRepository(before)
            val snapshot = github.task(repository, requireNotNull(before.number))
            if (snapshot.nodeId != before.nodeId || snapshot.kind != before.kind) throw GitHubApiException(invalidContract = true)
            tasks.ingestRepository(repository.id, snapshot)
        }
        val projectIds =
            withContext(Dispatchers.IO) {
                dataSource.inTransaction(readOnly = true) {
                    query("SELECT project_id FROM task_projects WHERE task_id=?", taskId) { it.getLong(1) }.toSet()
                }
            } + additionalProjects
        projectIds.forEach { id ->
            val project = integrations.project(id)
            if (!project.enabled || project.access != IntegrationAccess.AVAILABLE || project.nodeId == null) {
                if (before.kind == TaskKind.DRAFT || id in additionalProjects) throw invalidMutation()
                return@forEach // A disabled board must not disable independent Issue/PR operations.
            }
            if (project.productId != before.productId) throw invalidMutation()
            refreshBoard(project)
        }
        val raw = tasks.raw(taskId)
        return LoadedTask(raw, tasks.revision(taskId))
    }

    private suspend fun refreshBoard(project: ProjectIntegration) {
        val batch = sync.prepareBoard(project)
        withContext(Dispatchers.IO) { dataSource.memberTransaction { sync.apply(this, batch, clock.instant()) } }
    }

    private fun authorize(
        connection: Connection,
        userId: Long,
        scope: MutationScope,
    ) {
        val actor = authorization.requireCurrent(connection, userId)
        val writable =
            connection.query("SELECT state FROM provider_health WHERE provider='GITHUB' FOR UPDATE") { it.getString(1) }.singleOrNull() ==
                "HEALTHY"
        if (!writable) throw ApiFailure(HttpStatusCode.ServiceUnavailable, ApiErrorCode.GitHubReadOnly, "GitHub 연결이 복구될 때까지 읽기 전용입니다.")
        if (scope.taskId != null) {
            val product = taskProduct(connection, scope.taskId) ?: throw missingTask()
            authorization.requireTaskWrite(connection, userId, product, scope.taskId)
            val source =
                connection
                    .query("SELECT repository_id,kind FROM task_projections WHERE id=?", scope.taskId) {
                        it.getObject(1)?.let { value -> (value as Number).toLong() } to
                            TaskKind.valueOf(it.getString(2))
                    }.single()
            if (source.second != TaskKind.DRAFT &&
                connection
                    .query(
                        "SELECT 1 FROM repository_integrations WHERE id=? AND product_id=? AND enabled=TRUE AND access_state='AVAILABLE' AND github_node_id IS NOT NULL FOR UPDATE",
                        source.first,
                        product,
                    ) {
                        it.getInt(1)
                    }.isEmpty()
            ) {
                throw invalidMutation()
            }
        } else if (scope.product == null || !AuthorizationPolicy.canWriteProduct(actor, scope.product)) {
            throw accessDenied()
        }
        scope.repositoryId?.let { id ->
            val product = scope.product ?: taskProduct(connection, requireNotNull(scope.taskId))
            if (connection
                    .query(
                        "SELECT 1 FROM repository_integrations WHERE id=? AND product_id=? AND enabled=TRUE AND access_state='AVAILABLE' AND github_node_id IS NOT NULL FOR UPDATE",
                        id,
                        product,
                    ) {
                        it.getInt(1)
                    }.isEmpty()
            ) {
                throw invalidMutation()
            }
        }
        scope.projectId?.let { id ->
            val product = scope.product ?: taskProduct(connection, requireNotNull(scope.taskId))
            if (connection
                    .query(
                        "SELECT 1 FROM project_integrations WHERE id=? AND product_id=? AND enabled=TRUE AND access_state='AVAILABLE' AND github_node_id IS NOT NULL FOR UPDATE",
                        id,
                        product,
                    ) {
                        it.getInt(1)
                    }.isEmpty()
            ) {
                throw invalidMutation()
            }
        }
    }

    private fun taskProduct(
        connection: Connection,
        id: Long,
    ): ProductId? =
        connection
            .query("SELECT product_id FROM task_projections WHERE id=? FOR UPDATE", id) {
                ProductId.valueOf(it.getString(1))
            }.singleOrNull()

    private fun validateCandidates(
        connection: Connection,
        product: ProductId,
        ids: Set<Long>,
        expected: Map<Long, Long> = emptyMap(),
    ): Map<Long, Long> {
        validateIds(ids)
        return ids.associateWith { userId ->
            val numericId =
                connection
                    .query(
                        "SELECT g.github_user_id FROM users u JOIN user_pocs p ON p.user_id=u.id JOIN user_github_links g ON g.user_id=u.id WHERE u.id=? AND p.product_id=? AND u.is_active=TRUE AND g.github_user_id IS NOT NULL AND g.organization_membership='MEMBER' FOR UPDATE",
                        userId,
                        product,
                    ) { it.getLong(1) }
                    .singleOrNull() ?: throw ApiFailure.invalidRequest("새 담당자는 해당 제품의 활성 POC이며 유효한 GitHub 연결이 있어야 합니다.")
            if (expected[userId]?.let { it != numericId } ==
                true
            ) {
                throw ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "담당자의 GitHub 연결이 변경되었습니다. 최신 상태를 다시 확인해 주세요.")
            }
            numericId
        }
    }

    private suspend fun candidateActors(
        product: ProductId,
        userIds: Set<Long>,
    ): Map<Long, GitHubActor> {
        val ids =
            withContext(Dispatchers.IO) {
                dataSource.memberTransaction {
                    lockMemberManagement()
                    validateCandidates(this, product, userIds)
                }
            }
        return ids.mapValues { (_, numericId) -> mutations.actor(numericId) }
    }

    private suspend fun assignees(taskId: Long): List<GitHubActor> =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction(readOnly = true) {
                query(
                    "SELECT github_user_id,github_login,github_node_id FROM task_assignees WHERE task_id=? ORDER BY github_user_id",
                    taskId,
                ) {
                    GitHubActor(it.getLong(1), it.getString(2), it.getString(3))
                }
            }
        }.map { if (it.nodeId == null) mutations.actor(it.id) else it }

    private suspend fun reviewers(taskId: Long): List<Pair<GitHubActor, String>> =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction(readOnly = true) {
                query(
                    "SELECT github_user_id,github_login,github_node_id,request_occurrence_id FROM review_assignments WHERE task_id=? AND active=TRUE ORDER BY github_user_id",
                    taskId,
                ) {
                    GitHubActor(it.getLong(1), it.getString(2), it.getString(3)) to
                        it.getString(4)
                }
            }
        }

    private suspend fun projectContext(
        taskId: Long,
        projectId: Long,
    ): TaskProjectContext? =
        projectContexts(taskId).singleOrNull {
            it.projectId ==
                projectId
        }

    private suspend fun projectContexts(taskId: Long): List<TaskProjectContext> =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction(readOnly = true) {
                query(
                    "SELECT p.*,i.project_number FROM task_projects p JOIN project_integrations i ON i.id=p.project_id WHERE p.task_id=? ORDER BY p.project_id",
                    taskId,
                ) {
                    TaskProjectContext(
                        it.getLong("project_id"),
                        it.getInt("project_number"),
                        it.getString("item_node_id"),
                        it.getBoolean("archived"),
                        TaskStatus.valueOf(it.getString("status")),
                        TaskPriority.valueOf(it.getString("priority")),
                        it.getDate("start_date")?.toLocalDate()?.toString(),
                        it.getDate("target_date")?.toLocalDate()?.toString(),
                    )
                }
            }
        }

    private suspend fun requireRepository(task: StoredTask): RepositoryIntegration =
        enabledRepository(
            task.repositoryId ?: throw unsupportedMutation(),
        ).also { if (it.productId != task.productId) throw invalidMutation() }

    private suspend fun enabledRepository(id: Long): RepositoryIntegration =
        integrations.repository(id).also {
            if (!it.enabled ||
                it.access != IntegrationAccess.AVAILABLE ||
                it.nodeId == null
            ) {
                throw invalidMutation()
            }
        }

    private suspend fun enabledProject(id: Long): ProjectIntegration =
        integrations.project(id).also {
            if (!it.enabled ||
                it.access != IntegrationAccess.AVAILABLE ||
                it.nodeId == null ||
                it.number == 19
            ) {
                throw invalidMutation()
            }
        }

    private suspend fun staleSnapshot(
        userId: Long,
        taskId: Long,
    ): ApiFailure {
        val latest = tasks.detail(authorization.requireCurrent(userId), taskId)
        return ApiFailure(
            HttpStatusCode.Conflict,
            ApiErrorCode.Conflict,
            "GitHub 원본이 변경되었습니다. 최신 값을 확인한 뒤 다시 시도해 주세요.",
            buildJsonObject {
                put("latest", Json.encodeToJsonElement(latest))
            },
        )
    }

    private fun matchesIssue(
        task: LoadedTask,
        title: String?,
        body: String?,
        labels: List<String>,
        milestone: Int?,
    ): Boolean =
        (title == null || task.raw.title == title) && (body == null || task.raw.body == body) &&
            (task.raw.details["labels"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() == labels.toSet() &&
            (task.raw.details["milestone"] as? JsonObject)?.number("number") == milestone?.toLong()

    private data class LoadedTask(
        val raw: StoredTask,
        val revision: String,
    )

    private data class MutationScope(
        val taskId: Long? = null,
        val product: ProductId? = null,
        val repositoryId: Long? = null,
        val projectId: Long? = null,
    )

    private data class Confirmation(
        val confirmed: Boolean,
        val taskId: Long?,
    )

    private class MutationPlan(
        val dispatch: suspend (DispatchContext) -> Unit,
        val confirm: suspend (JsonObject) -> Confirmation,
    ) {
        var preDispatch: (Connection) -> Unit = {}
    }
}

private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject.textValue(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.ids(key: String): Set<Long> =
    (get(key) as? JsonArray)
        ?.mapNotNull {
            (it as? JsonPrimitive)?.longOrNull
        }?.toSet()
        .orEmpty()

private fun requireRevision(value: String?) {
    if (value == null ||
        !value.matches(Regex("[0-9a-f]{64}"))
    ) {
        throw ApiFailure.invalidRequest("최신 태스크 revision이 필요합니다.")
    }
}

private fun validateText(
    title: String,
    body: String,
) {
    if (title.isBlank() || title.trim().length > 256 || body.length > MAX_BODY ||
        '\u0000' in title ||
        '\u0000' in body
    ) {
        throw invalidMutation()
    }
}

private fun validateLabels(
    labels: List<String>,
    milestone: Int?,
) {
    if (labels.size > 100 ||
        labels.any { it.isBlank() || it.length > 50 || '\u0000' in it } ||
        (milestone != null && milestone <= 0)
    ) {
        throw invalidMutation()
    }
}

private fun validateIds(ids: Set<Long>) {
    if (ids.size > 10 || ids.any { it <= 0 }) throw invalidMutation()
}

private fun invalidMutation(): ApiFailure = ApiFailure.invalidRequest("태스크 변경 요청 또는 연동 상태가 올바르지 않습니다.")

private fun unsupportedMutation(): ApiFailure =
    ApiFailure(HttpStatusCode.Forbidden, ApiErrorCode.AccessDenied, "이 태스크 유형에서는 해당 작업을 수행할 수 없습니다. GitHub 원본을 이용해 주세요.")

private fun missingTask(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "태스크를 찾을 수 없습니다.")

private fun failedOperation(operationId: String): ApiFailure =
    ApiFailure(
        HttpStatusCode.Conflict,
        ApiErrorCode.Conflict,
        "이 작업은 변경 없이 종료되었습니다. 최신 상태를 확인한 뒤 새 작업을 시작해 주세요.",
        JsonObject(mapOf("operationId" to JsonPrimitive(operationId), "outcome" to JsonPrimitive("not_applied"))),
    )

private const val MAX_BODY = 65_536
