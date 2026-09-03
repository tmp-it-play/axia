package team.themoment.axia.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import team.themoment.axia.analytics.CompletionAnalyticsResponse
import team.themoment.axia.analytics.CompletionHistoryEntry
import team.themoment.axia.analytics.CompletionHistoryResponse
import team.themoment.axia.analytics.MemberAnalyticsResponse
import team.themoment.axia.analytics.OperationsAnalyticsResponse
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.feature.tasks.TaskWriteCommand
import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.MemberDetail
import team.themoment.axia.members.MemberListResponse
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.notifications.GlobalReminderPolicy
import team.themoment.axia.notifications.NotificationListResponse
import team.themoment.axia.notifications.NotificationSummary
import team.themoment.axia.notifications.RecentAssignmentActivityResponse
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.products.IntegrationListResponse
import team.themoment.axia.products.ProductListResponse
import team.themoment.axia.products.ProductSummary
import team.themoment.axia.products.ProjectFieldCatalog
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.tasks.BoardReminderPolicy
import team.themoment.axia.tasks.GitHubAvailability
import team.themoment.axia.tasks.MutationResult
import team.themoment.axia.tasks.TaskDetail
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskListResponse
import team.themoment.axia.tasks.TaskSummary
import team.themoment.axia.unavailability.UnavailabilityListResponse
import team.themoment.axia.unavailability.UnavailabilityPeriod
import kotlin.coroutines.cancellation.CancellationException

data class TaskFilters(
    val productId: ProductId? = null,
    val userId: Long? = null,
    val position: TechnicalPosition? = null,
    val kind: TaskKind? = null,
    val unassigned: Boolean = false,
    val includeClosed: Boolean = false,
    val search: String = "",
)

data class WorkspaceUiState(
    val currentUser: CurrentUserResponse? = null,
    val members: List<MemberSummary> = emptyList(),
    val products: List<ProductSummary> = emptyList(),
    val tasks: List<TaskSummary> = emptyList(),
    val myTasks: List<TaskSummary> = emptyList(),
    val calendarTasks: List<TaskSummary> = emptyList(),
    val dashboardCompletions: List<CompletionHistoryEntry> = emptyList(),
    val dashboardAssignments: List<NotificationSummary> = emptyList(),
    val selectedTask: TaskDetail? = null,
    val selectedMember: MemberDetail? = null,
    val selectedProduct: ProductId = ProductId.HELLO_GSM,
    val integrations: IntegrationListResponse? = null,
    val fieldCatalog: ProjectFieldCatalog? = null,
    val filters: TaskFilters = TaskFilters(),
    val github: GitHubAvailability = GitHubAvailability(),
    val loading: Boolean = false,
    val detailLoading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val refreshError: String? = null,
    val notice: String? = null,
    val pendingTaskWrite: TaskWriteCommand? = null,
    val confirmedTaskWriteId: String? = null,
    val periods: List<UnavailabilityPeriod> = emptyList(),
    val periodsLoading: Boolean = false,
    val notifications: List<NotificationSummary> = emptyList(),
    val notificationCursor: Long? = null,
    val unreadCount: Long = 0,
    val unreadOnly: Boolean = false,
    val inboxLoading: Boolean = false,
    val completionAnalytics: CompletionAnalyticsResponse? = null,
    val memberAnalytics: MemberAnalyticsResponse? = null,
    val operationsAnalytics: OperationsAnalyticsResponse? = null,
    val analyticsLoading: Boolean = false,
    val history: List<CompletionHistoryEntry> = emptyList(),
    val historyCursor: Long? = null,
    val historyUserId: Long? = null,
    val historyProductId: ProductId? = null,
    val historyLoading: Boolean = false,
    val reminderProjects: List<ProjectIntegration> = emptyList(),
    val globalReminderPolicy: GlobalReminderPolicy? = null,
    val boardReminderPolicies: Map<Long, BoardReminderPolicy> = emptyMap(),
    val selectedReminderProjectId: Long? = null,
    val reminderPoliciesLoading: Boolean = false,
)

@Stable
class WorkspaceController(
    val gateway: WorkspaceGateway,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(WorkspaceUiState())
        private set
    private var refreshJob: Job? = null
    private var taskDetailJob: Job? = null
    private var memberDetailJob: Job? = null
    private var integrationJob: Job? = null
    private var periodsJob: Job? = null
    private var inboxJob: Job? = null
    private var analyticsJob: Job? = null
    private var historyJob: Job? = null
    private var reminderPoliciesJob: Job? = null
    private var refreshGeneration = 0L
    private var taskGeneration = 0L
    private var memberGeneration = 0L
    private var integrationGeneration = 0L
    private var periodsGeneration = 0L
    private var inboxGeneration = 0L
    private var analyticsGeneration = 0L
    private var historyGeneration = 0L
    private var reminderPoliciesGeneration = 0L
    private var periodsRange: Pair<Long, Long>? = null
    private var onTaskWriteConfirmed: (suspend (MutationResult) -> Unit)? = null

    fun refresh() {
        val generation = ++refreshGeneration
        val filters = state.filters
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                // Polling must not dismiss errors from a separate user action or post-save refresh.
                state = state.copy(loading = true, refreshError = null)
                try {
                    val current = gateway.get<CurrentUserResponse>("/v1/me")
                    if (generation != refreshGeneration) return@launch
                    // Re-read capability changes even when a provider's task projection is unavailable.
                    state = state.copy(currentUser = current)
                    coroutineScope {
                        val members = async { gateway.get<MemberListResponse>("/v1/members?activeOnly=false") }
                        val products = async { gateway.get<ProductListResponse>("/v1/products") }
                        val tasks = async { allTasks(filters) }
                        val mine = async { allTasks(TaskFilters(userId = current.member.id)) }
                        val completions =
                            async { gateway.get<CompletionHistoryResponse>("/v1/completions?userId=${current.member.id}&limit=5") }
                        val assignments =
                            async { gateway.get<RecentAssignmentActivityResponse>("/v1/notifications/recent-assignments") }
                        val fetchedTasks = tasks.await()
                        val fetchedMembers = members.await()
                        val fetchedProducts = products.await()
                        val fetchedMine = mine.await()
                        val fetchedCompletions = completions.await()
                        val fetchedAssignments = assignments.await()
                        if (generation == refreshGeneration) {
                            state =
                                state.copy(
                                    members = fetchedMembers.members,
                                    products = fetchedProducts.products,
                                    tasks = fetchedTasks.tasks,
                                    myTasks = fetchedMine.tasks,
                                    github = fetchedTasks.github,
                                    dashboardCompletions = fetchedCompletions.completions,
                                    dashboardAssignments = fetchedAssignments.assignments,
                                )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == refreshGeneration) showRefreshError(failure)
                } finally {
                    if (generation == refreshGeneration) state = state.copy(loading = false)
                }
            }
    }

    fun filterTasks(filters: TaskFilters) {
        closeTask()
        state = state.copy(filters = filters)
        refresh()
    }

    fun selectTask(
        id: Long,
        assignmentKey: String? = null,
    ) {
        val generation = ++taskGeneration
        taskDetailJob?.cancel()
        state = state.copy(selectedTask = null, detailLoading = true)
        taskDetailJob =
            scope.launch {
                try {
                    val detail =
                        gateway.get<TaskDetail>(
                            "/v1/tasks/$id" + (assignmentKey?.let { "?" + queryString(mapOf("assignmentKey" to it)) } ?: ""),
                        )
                    if (generation == taskGeneration) {
                        state = state.copy(selectedTask = detail)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == taskGeneration) showError(failure)
                } finally {
                    if (generation == taskGeneration) state = state.copy(detailLoading = false)
                }
            }
    }

    fun closeTask() {
        taskGeneration++
        taskDetailJob?.cancel()
        state = state.copy(selectedTask = null, detailLoading = false)
    }

    fun selectMember(id: Long) {
        val generation = ++memberGeneration
        memberDetailJob?.cancel()
        state = state.copy(selectedMember = null, detailLoading = true)
        memberDetailJob =
            scope.launch {
                try {
                    val detail = gateway.get<MemberDetail>("/v1/members/$id")
                    if (generation == memberGeneration) {
                        state = state.copy(selectedMember = detail)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == memberGeneration) showError(failure)
                } finally {
                    if (generation == memberGeneration) state = state.copy(detailLoading = false)
                }
            }
    }

    fun closeMember() {
        memberGeneration++
        memberDetailJob?.cancel()
        state = state.copy(selectedMember = null, detailLoading = false)
    }

    fun selectProduct(product: ProductId) {
        val generation = ++integrationGeneration
        integrationJob?.cancel()
        state = state.copy(selectedProduct = product, integrations = null, fieldCatalog = null, detailLoading = true)
        integrationJob =
            scope.launch {
                try {
                    val integrations = gateway.get<IntegrationListResponse>("/v1/products/${product.name}/integrations")
                    if (generation == integrationGeneration) {
                        state = state.copy(integrations = integrations)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == integrationGeneration) showError(failure)
                } finally {
                    if (generation == integrationGeneration) state = state.copy(detailLoading = false)
                }
            }
    }

    fun loadFieldCatalog(projectId: Long) {
        state = state.copy(fieldCatalog = null)
        scope.launch {
            try {
                val catalog = gateway.get<ProjectFieldCatalog>("/v1/projects/$projectId/field-catalog")
                if (state.integrations?.projects?.any { it.id == projectId } == true) state = state.copy(fieldCatalog = catalog)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showError(failure)
            }
        }
    }

    fun mutate(
        method: String,
        path: String,
        body: String? = null,
        onResponse: (WorkspaceResponse) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, refreshError = null, notice = null)
        scope.launch {
            try {
                val response = gateway.checked(method, path, body)
                state = state.copy(notice = "변경을 저장했습니다.")
                onResponse(response)
                onSuccess()
                refresh()
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (failure: Exception) {
                showError(failure)
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    /** This is outcome confirmation, not an offline write queue. The original request is never rebuilt. */
    fun submitTaskWrite(
        command: TaskWriteCommand,
        onConfirmed: suspend (MutationResult) -> Unit = {},
    ) {
        if (state.busy) return
        if (state.pendingTaskWrite != null) {
            state =
                state.copy(
                    error =
                        "먼저 이전 GitHub 변경의 결과를 확인해 주세요. " +
                            "새 변경은 아직 전송하지 않았습니다.",
                )
            return
        }
        onTaskWriteConfirmed = onConfirmed
        state = state.copy(pendingTaskWrite = command, confirmedTaskWriteId = null)
        executeTaskWrite(command, confirming = false)
    }

    fun confirmPendingTaskWrite() {
        if (state.busy) return
        state.pendingTaskWrite?.let { executeTaskWrite(it, confirming = true) }
    }

    private fun executeTaskWrite(
        command: TaskWriteCommand,
        confirming: Boolean,
    ) {
        state = state.copy(busy = true, error = null, refreshError = null, notice = null)
        scope.launch {
            try {
                val response = gateway.checked(command.method, command.path, command.body)
                val result = workspaceJson.decodeFromString<MutationResult>(response.body)
                check(result.operationId == command.operationId) { "Mismatched operation receipt" }
                if (!result.confirmed) {
                    state =
                        state.copy(
                            notice =
                                "GitHub 변경 결과를 확인 중입니다. " +
                                    "같은 작업의 결과 확인만 가능하며, 변경을 새로 전송하지 않습니다.",
                        )
                    if (result.refreshRequired) refresh()
                    return@launch
                }
                val callback = onTaskWriteConfirmed
                onTaskWriteConfirmed = null
                state =
                    state.copy(
                        pendingTaskWrite = null,
                        confirmedTaskWriteId = command.operationId,
                        notice = "GitHub에 변경을 저장했습니다.",
                    )
                result.taskId?.let { if (state.selectedTask?.task?.id == command.taskId || command.taskId == null) selectTask(it) }
                refresh()
                // A refresh/Undo preparation failure after confirmation must never turn a saved write into a retry.
                try {
                    callback?.invoke(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state =
                        state.copy(
                            error =
                                "GitHub 저장은 완료됐지만 최신 화면을 불러오지 못했습니다. " +
                                    "새로고침해 주세요.",
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is WorkspaceFailure) {
                    val terminal =
                        failure.error.details?.let { details ->
                            (details["operationId"] as? JsonPrimitive)?.contentOrNull == command.operationId &&
                                (details["outcome"] as? JsonPrimitive)?.contentOrNull == "not_applied"
                        } == true
                    // A rejection of a later confirmation (expired intent, lost permissions, outage) says
                    // nothing about the original write. Keep that uncertain intent locked in memory.
                    val rejectedBeforeWrite = !confirming && failure.status in setOf(400, 401, 403, 404, 409, 410, 422)
                    if (terminal || rejectedBeforeWrite) {
                        onTaskWriteConfirmed = null
                        state = state.copy(pendingTaskWrite = null)
                    }
                    failure.error.details?.get("latest")?.let { latest ->
                        runCatching { workspaceJson.decodeFromJsonElement<TaskDetail>(latest) }.getOrNull()?.let { detail ->
                            if (detail.task.id == command.taskId) {
                                fun matches(task: TaskSummary): Boolean =
                                    task.id == detail.task.id && task.kind == detail.task.kind &&
                                        task.assignmentKey == detail.task.assignmentKey

                                fun replace(tasks: List<TaskSummary>): List<TaskSummary> =
                                    tasks.map { if (matches(it)) detail.task else it }

                                state =
                                    state.copy(
                                        selectedTask =
                                            if (state.selectedTask?.task?.let(::matches) == true) detail else state.selectedTask,
                                        tasks = replace(state.tasks),
                                        myTasks = replace(state.myTasks),
                                        calendarTasks = replace(state.calendarTasks),
                                    )
                                // Re-evaluate filtered membership without losing the immediate conflict snapshot.
                                refresh()
                                reloadPeriods()
                            }
                        }
                    }
                }
                showError(failure)
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    /** Delayed save/Undo callbacks refresh the displayed range, not the month they captured. */
    fun reloadPeriods() {
        val (from, until) = periodsRange ?: return
        loadPeriods(from, until)
    }

    fun loadPeriods(
        from: Long,
        until: Long,
    ) {
        periodsRange = from to until
        val generation = ++periodsGeneration
        periodsJob?.cancel()
        state = state.copy(periodsLoading = true, refreshError = null)
        periodsJob =
            scope.launch {
                try {
                    coroutineScope {
                        val periods =
                            async {
                                gateway.get<UnavailabilityListResponse>(
                                    "/v1/unavailability?fromEpochMillis=$from&untilEpochMillis=$until",
                                )
                            }
                        val tasks = async { allTasks(TaskFilters(includeClosed = true)) }
                        val result = periods.await()
                        val calendar = tasks.await()
                        if (generation ==
                            periodsGeneration
                        ) {
                            state =
                                state.copy(periods = result.periods, calendarTasks = calendar.tasks, github = calendar.github)
                        }
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    if (generation == periodsGeneration) showRefreshError(failure)
                } finally {
                    if (generation == periodsGeneration) state = state.copy(periodsLoading = false)
                }
            }
    }

    fun loadInbox(
        unreadOnly: Boolean = state.unreadOnly,
        more: Boolean = false,
    ) {
        if (more && state.notificationCursor == null) return
        val generation = ++inboxGeneration
        inboxJob?.cancel()
        val before = state.notificationCursor.takeIf { more }
        state = state.copy(inboxLoading = true, unreadOnly = unreadOnly)
        inboxJob =
            scope.launch {
                try {
                    val result =
                        gateway.get<NotificationListResponse>(
                            "/v1/notifications?unreadOnly=$unreadOnly" + (before?.let { "&beforeId=$it" } ?: ""),
                        )
                    if (generation == inboxGeneration) {
                        state =
                            state.copy(
                                notifications =
                                    (if (more) state.notifications + result.notifications else result.notifications).distinctBy {
                                        it.id
                                    },
                                notificationCursor = result.nextCursor,
                                unreadCount = result.unreadCount,
                            )
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    if (generation == inboxGeneration) showError(failure)
                } finally {
                    if (generation == inboxGeneration) state = state.copy(inboxLoading = false)
                }
            }
    }

    fun loadAnalytics(query: String = "range=LAST_30_DAYS") {
        val generation = ++analyticsGeneration
        analyticsJob?.cancel()
        state = state.copy(analyticsLoading = true)
        analyticsJob =
            scope.launch {
                try {
                    coroutineScope {
                        val completions = async { gateway.get<CompletionAnalyticsResponse>("/v1/analytics/completions?$query") }
                        val members = async { gateway.get<MemberAnalyticsResponse>("/v1/analytics/members?$query") }
                        val operations = async { gateway.get<OperationsAnalyticsResponse>("/v1/analytics/operations") }
                        val result = Triple(completions.await(), members.await(), operations.await())
                        if (generation ==
                            analyticsGeneration
                        ) {
                            state =
                                state.copy(
                                    completionAnalytics = result.first,
                                    memberAnalytics = result.second,
                                    operationsAnalytics = result.third,
                                )
                        }
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    if (generation == analyticsGeneration) showError(failure)
                } finally {
                    if (generation == analyticsGeneration) state = state.copy(analyticsLoading = false)
                }
            }
    }

    fun loadHistory(
        userId: Long? = state.historyUserId,
        productId: ProductId? = state.historyProductId,
        more: Boolean = false,
    ) {
        if (more && state.historyCursor == null) return
        val generation = ++historyGeneration
        historyJob?.cancel()
        val before = state.historyCursor.takeIf { more }
        state = state.copy(historyLoading = true, historyUserId = userId, historyProductId = productId)
        historyJob =
            scope.launch {
                try {
                    val query =
                        queryString(
                            buildMap {
                                userId?.let { put("userId", it.toString()) }
                                productId?.let { put("productId", it.name) }
                                before?.let { put("beforeId", it.toString()) }
                            },
                        )
                    val result = gateway.get<CompletionHistoryResponse>("/v1/completions?$query")
                    if (generation ==
                        historyGeneration
                    ) {
                        state =
                            state.copy(
                                history =
                                    (if (more) state.history + result.completions else result.completions).distinctBy {
                                        it.id
                                    },
                                historyCursor = result.nextCursor,
                            )
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    if (generation == historyGeneration) showError(failure)
                } finally {
                    if (generation == historyGeneration) state = state.copy(historyLoading = false)
                }
            }
    }

    fun loadReminderPolicies(projectId: Long? = state.selectedReminderProjectId) {
        val current = state.currentUser ?: return
        val generation = ++reminderPoliciesGeneration
        reminderPoliciesJob?.cancel()
        state = state.copy(reminderPoliciesLoading = true)
        reminderPoliciesJob =
            scope.launch {
                try {
                    coroutineScope {
                        val integrations =
                            current.capabilities.managedIntegrationProducts.map { product ->
                                async { gateway.get<IntegrationListResponse>("/v1/products/${product.name}/integrations") }
                            }
                        val mayManageGlobal =
                            current.capabilities.effectiveSystemAdministrator ||
                                current.member.organizationRole == OrganizationRole.C_LEADER
                        val global = async { if (mayManageGlobal) gateway.get<GlobalReminderPolicy>("/v1/reminder-policy") else null }
                        val projects =
                            integrations
                                .flatMap {
                                    it.await().projects
                                }.sortedWith(compareBy<ProjectIntegration> { it.productId.ordinal }.thenBy { it.number })
                        val selected = projects.firstOrNull { it.id == projectId }?.id ?: projects.firstOrNull()?.id
                        val policy = selected?.let { gateway.get<BoardReminderPolicy>("/v1/projects/$it/reminder-policy") }
                        val globalPolicy = global.await()
                        if (generation == reminderPoliciesGeneration) {
                            state =
                                state.copy(
                                    reminderProjects = projects,
                                    selectedReminderProjectId = selected,
                                    globalReminderPolicy = globalPolicy,
                                    boardReminderPolicies =
                                        state.boardReminderPolicies.filterKeys { id -> projects.any { it.id == id } } +
                                            (if (selected != null && policy != null) mapOf(selected to policy) else emptyMap()),
                                )
                        }
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    if (generation == reminderPoliciesGeneration) showError(failure)
                } finally {
                    if (generation == reminderPoliciesGeneration) state = state.copy(reminderPoliciesLoading = false)
                }
            }
    }

    fun dismissMessage() {
        state = state.copy(error = null, refreshError = null, notice = null)
    }

    private suspend fun allTasks(filters: TaskFilters): TaskListResponse {
        var cursor: Long? = null
        val tasks = mutableListOf<TaskSummary>()
        val seenCursors = mutableSetOf<Long>()
        var availability = GitHubAvailability()
        do {
            val parameters =
                buildMap {
                    filters.productId?.let { put("productId", it.name) }
                    filters.userId?.let { put("userId", it.toString()) }
                    filters.position?.let { put("position", it.name) }
                    filters.kind?.let { put("kind", it.name) }
                    put("unassignedOnly", filters.unassigned.toString())
                    put("includeClosed", filters.includeClosed.toString())
                    put("search", filters.search)
                    cursor?.let { put("cursor", it.toString()) }
                }
            val page = gateway.get<TaskListResponse>("/v1/tasks?" + queryString(parameters))
            tasks += page.tasks
            availability = page.github
            cursor = page.nextCursor
            if (cursor != null && !seenCursors.add(cursor)) error("Invalid task pagination cursor")
        } while (cursor != null)
        return TaskListResponse(tasks, github = availability)
    }

    private fun showRefreshError(failure: Exception) {
        if (state.error == null) {
            showError(failure)
        } else {
            state = state.copy(refreshError = errorMessage(failure))
        }
    }

    private fun showError(failure: Exception) {
        state = state.copy(error = errorMessage(failure), refreshError = null)
    }

    private fun errorMessage(failure: Exception): String =
        if (failure is WorkspaceFailure) {
            failure.error.message
        } else {
            "서버와 연결하지 못했습니다. 네트워크 상태를 확인하고 다시 시도해 주세요."
        }
}

internal fun queryString(values: Map<String, String>): String =
    values.entries.joinToString("&") { (key, value) -> "${percentEncode(key)}=${percentEncode(value)}" }

private fun percentEncode(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 255
            if (unsigned in 65..90 || unsigned in 97..122 || unsigned in 48..57 || unsigned.toChar() in "-._~") {
                append(unsigned.toChar())
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
