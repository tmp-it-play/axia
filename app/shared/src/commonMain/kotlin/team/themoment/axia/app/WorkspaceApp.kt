package team.themoment.axia.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.serialization.encodeToString
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import team.themoment.axia.domain.ProductId
import team.themoment.axia.feature.analytics.AnalyticsCallbacks
import team.themoment.axia.feature.analytics.AnalyticsFilterDraft
import team.themoment.axia.feature.analytics.AnalyticsScreen
import team.themoment.axia.feature.analytics.AnalyticsUiState
import team.themoment.axia.feature.analytics.CompletionHistoryCallbacks
import team.themoment.axia.feature.analytics.CompletionHistoryScreen
import team.themoment.axia.feature.analytics.CompletionHistoryUiState
import team.themoment.axia.feature.calendar.CalendarCallbacks
import team.themoment.axia.feature.calendar.CalendarFilters
import team.themoment.axia.feature.calendar.CalendarScreen
import team.themoment.axia.feature.calendar.calendarDays
import team.themoment.axia.feature.calendar.calendarTargetCommand
import team.themoment.axia.feature.calendar.movedPeriod
import team.themoment.axia.feature.dashboard.DashboardScreen
import team.themoment.axia.feature.members.MemberCallbacks
import team.themoment.axia.feature.members.MemberDetailTab
import team.themoment.axia.feature.members.MemberEditorDraft
import team.themoment.axia.feature.members.MembersScreen
import team.themoment.axia.feature.members.MembersUiState
import team.themoment.axia.feature.notifications.NotificationCallbacks
import team.themoment.axia.feature.notifications.NotificationCenterScreen
import team.themoment.axia.feature.notifications.NotificationUiState
import team.themoment.axia.feature.products.ProductCallbacks
import team.themoment.axia.feature.products.ProductsScreen
import team.themoment.axia.feature.reminders.ReminderPolicyCallbacks
import team.themoment.axia.feature.reminders.ReminderPolicyScreen
import team.themoment.axia.feature.reminders.ReminderPolicyUiState
import team.themoment.axia.feature.settings.SettingsScreen
import team.themoment.axia.feature.tasks.TaskDetailActions
import team.themoment.axia.feature.tasks.TaskWriteDialog
import team.themoment.axia.feature.tasks.TaskWriteMode
import team.themoment.axia.feature.tasks.TasksScreen
import team.themoment.axia.feature.unavailability.UnavailabilityCallbacks
import team.themoment.axia.feature.unavailability.UnavailabilityEditorDraft
import team.themoment.axia.feature.unavailability.UnavailabilityScreen
import team.themoment.axia.feature.unavailability.UnavailabilityUiState
import team.themoment.axia.members.AnalyticsScopeKind
import team.themoment.axia.navigation.AxiaAccessFlags
import team.themoment.axia.navigation.AxiaDestination
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.products.AddProjectRequest
import team.themoment.axia.products.AddRepositoryRequest
import team.themoment.axia.products.DefaultProjectRequest
import team.themoment.axia.products.ProjectFieldMappingRequest
import team.themoment.axia.products.UpdateIntegrationRequest
import team.themoment.axia.tasks.ReminderOverrideRequest
import team.themoment.axia.tasks.ReminderSuppressionRequest
import team.themoment.axia.tasks.TaskDetail
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.unavailability.UnavailabilityCreateRequest
import team.themoment.axia.unavailability.UnavailabilityPeriod
import team.themoment.axia.unavailability.UnavailabilityUpdateRequest

private data class WorkspaceUndo(
    val label: String,
    val target: WorkspaceUndoTarget = WorkspaceUndoTarget.AxiaDatabase,
    val execute: () -> Unit,
)

private enum class WorkspaceUndoTarget { AxiaDatabase, GitHub }

private enum class ManagementSection { Members, Integrations, Reminders }

@Composable
fun WorkspaceApp(
    gateway: WorkspaceGateway,
    themeMode: AxiaThemeMode,
    onThemeModeChange: (AxiaThemeMode) -> Unit,
    onManageSessions: () -> Unit,
    onLogout: () -> Unit,
    onCheckUpdates: () -> Unit,
    initialDestination: AxiaDestination,
    updateStatusMessage: String? = null,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(gateway) { WorkspaceController(gateway, scope) }
    val state = controller.state
    val uri = LocalUriHandler.current
    val capabilities = state.currentUser?.capabilities
    val flags =
        AxiaAccessFlags(
            canViewAnalytics = capabilities?.analyticsScope?.kind?.let { it != AnalyticsScopeKind.NONE } == true,
            canManageOrganization =
                capabilities?.let {
                    it.canManageOrganizationRoles || it.canManageExplicitAdmins || it.managedTechnicalPositions.isNotEmpty()
                } ==
                    true,
            canManageProducts = capabilities?.canManagePocs == true,
            canManageIntegrations = capabilities?.managedIntegrationProducts?.isNotEmpty() == true,
        )
    val appState = rememberAxiaAppState(flags, initialDestination)
    var memberSearch by remember { mutableStateOf("") }
    var selectedMemberId by remember { mutableStateOf<Long?>(null) }
    var memberTab by remember { mutableStateOf(MemberDetailTab.Profile) }
    var memberEditor by remember { mutableStateOf<MemberEditorDraft?>(null) }
    var showingHistory by remember { mutableStateOf(false) }
    var analyticsFilter by remember { mutableStateOf(AnalyticsFilterDraft()) }
    var managementSection by remember { mutableStateOf(ManagementSection.Members) }
    var periodUserId by remember { mutableStateOf<Long?>(null) }
    var periodEditor by remember { mutableStateOf<UnavailabilityEditorDraft?>(null) }
    var periodDeleteId by remember { mutableStateOf<Long?>(null) }
    var calendarFilters by remember { mutableStateOf(CalendarFilters()) }
    var taskWriteMode by remember { mutableStateOf<TaskWriteMode?>(null) }
    var month by remember {
        val today = AxiaTime.today()
        mutableStateOf(LocalDate(today.year, today.month, 1))
    }
    var undo by remember { mutableStateOf<WorkspaceUndo?>(null) }
    val calendarDates = remember(month) { calendarDays(month) }
    val calendarFrom = calendarDates.first().atStartOfDayIn(AxiaTime.zone).toEpochMilliseconds()
    val calendarUntil =
        calendarDates
            .last()
            .plus(1, DateTimeUnit.DAY)
            .atStartOfDayIn(AxiaTime.zone)
            .toEpochMilliseconds()

    fun reloadPeriods() {
        controller.loadPeriods(calendarFrom, calendarUntil)
    }

    fun openTask(id: Long) {
        appState.navigate(AxiaDestination.Tasks)
        controller.selectTask(id)
    }

    fun openMember(id: Long) {
        showingHistory = false
        selectedMemberId = id
        memberTab = MemberDetailTab.Profile
        appState.navigate(AxiaDestination.Members)
        controller.selectMember(id)
    }

    fun openPeriods(userId: Long?) {
        periodUserId = userId
        calendarFilters = calendarFilters.copy(userId = userId)
        appState.navigate(AxiaDestination.Calendar)
    }

    fun openHistory(userId: Long?) {
        showingHistory = true
        controller.loadHistory(userId = userId)
        appState.navigate(AxiaDestination.Members)
    }

    fun afterPeriodSave() {
        periodEditor = null
        periodDeleteId = null
        reloadPeriods()
    }

    fun openTaskWrite(mode: TaskWriteMode) {
        if (state.busy || state.pendingTaskWrite != null) return
        val creating = mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CREATE_DRAFT)
        val product =
            if (creating) {
                state.selectedProduct.takeIf { it in capabilities?.writableProducts.orEmpty() }
                    ?: capabilities?.writableProducts?.firstOrNull() ?: return
            } else {
                state.selectedTask?.task?.productId ?: return
            }
        controller.dismissMessage()
        controller.selectProduct(product)
        taskWriteMode = mode
    }

    LaunchedEffect(controller) {
        controller.loadInbox()
        while (true) {
            controller.refresh()
            delay(60_000)
            controller.loadInbox()
        }
    }
    LaunchedEffect(state.selectedMember) { memberEditor = state.selectedMember?.let(MemberEditorDraft::from) }
    LaunchedEffect(state.confirmedTaskWriteId) { if (state.confirmedTaskWriteId != null) taskWriteMode = null }
    LaunchedEffect(appState.currentDestination, state.currentUser?.member?.id, month) {
        when (appState.currentDestination) {
            AxiaDestination.Products -> {
                controller.selectProduct(state.selectedProduct)
            }

            AxiaDestination.Calendar -> {
                reloadPeriods()
            }

            AxiaDestination.Analytics -> {
                if (flags.canViewAnalytics) controller.loadAnalytics(analyticsFilter.query())
            }

            AxiaDestination.Notifications -> {
                controller.loadInbox()
            }

            AxiaDestination.Management -> {
                when (managementSection) {
                    ManagementSection.Integrations -> controller.selectProduct(state.selectedProduct)
                    ManagementSection.Reminders -> controller.loadReminderPolicies()
                    ManagementSection.Members -> Unit
                }
            }

            else -> {}
        }
    }

    val memberCallbacks =
        MemberCallbacks(
            onRefresh = {
                controller.refresh()
                selectedMemberId?.let(controller::selectMember)
            },
            onSearchChange = { memberSearch = it },
            onSelectMember = {
                selectedMemberId = it
                controller.selectMember(it)
            },
            onCloseDetail = {
                selectedMemberId = null
                memberEditor = null
                controller.closeMember()
            },
            onDetailTabChange = { memberTab = it },
            onEditorChange = { memberEditor = it },
            onSaveRole = {
                id,
                request,
                ->
                controller.mutate("PUT", "/v1/members/$id/organization-role", workspaceJson.encodeToString(request), onSuccess = {
                    controller.selectMember(id)
                })
            },
            onSavePositions = {
                id,
                request,
                ->
                controller.mutate(
                    "PUT",
                    "/v1/members/$id/positions",
                    workspaceJson.encodeToString(request),
                    onSuccess = { controller.selectMember(id) },
                )
            },
            onSavePocs = {
                id,
                request,
                ->
                controller.mutate(
                    "PUT",
                    "/v1/members/$id/pocs",
                    workspaceJson.encodeToString(request),
                    onSuccess = { controller.selectMember(id) },
                )
            },
            onSaveExplicitAdmin = {
                id,
                request,
                ->
                controller.mutate("PUT", "/v1/members/$id/explicit-admin", workspaceJson.encodeToString(request), onSuccess = {
                    controller.selectMember(id)
                })
            },
            onOpenUnavailability = ::openPeriods,
            onBrowseMemberTasks = { id ->
                controller.filterTasks(TaskFilters(userId = id))
                appState.navigate(AxiaDestination.Tasks)
            },
            onBrowseMemberHistory = ::openHistory,
        )
    val periodCallbacks =
        UnavailabilityCallbacks(
            onRefresh = ::reloadPeriods,
            onFilterUser = {
                periodUserId = it
                calendarFilters = calendarFilters.copy(userId = it)
            },
            onCreate = { periodEditor = UnavailabilityEditorDraft.new(it, maxOf(AxiaTime.nowEpochMillis(), calendarFrom)) },
            onEdit = { periodEditor = UnavailabilityEditorDraft.from(it) },
            onEditorChange = { periodEditor = it },
            onCancelEdit = { periodEditor = null },
            onCreatePeriod = { request ->
                controller.mutate("POST", "/v1/unavailability", workspaceJson.encodeToString(request), onResponse = { response ->
                    val saved = workspaceJson.decodeFromString<UnavailabilityPeriod>(response.body)
                    undo =
                        WorkspaceUndo("일정 등록 되돌리기") {
                            controller.mutate(
                                "DELETE",
                                "/v1/unavailability/${saved.id}?expectedVersion=${saved.version}",
                                onSuccess = ::reloadPeriods,
                            )
                        }
                }, onSuccess = ::afterPeriodSave)
            },
            onUpdatePeriod = { id, request ->
                val original = state.periods.singleOrNull { it.id == id }
                controller.mutate("PATCH", "/v1/unavailability/$id", workspaceJson.encodeToString(request), onResponse = { response ->
                    val saved = workspaceJson.decodeFromString<UnavailabilityPeriod>(response.body)
                    if (original != null) {
                        undo =
                            WorkspaceUndo("일정 변경 되돌리기") {
                                val inverse =
                                    UnavailabilityUpdateRequest(
                                        original.type,
                                        original.startsAtEpochMillis,
                                        original.endsAtEpochMillis,
                                        original.reason,
                                        saved.version,
                                    )
                                controller.mutate(
                                    "PATCH",
                                    "/v1/unavailability/$id",
                                    workspaceJson.encodeToString(inverse),
                                    onSuccess = ::reloadPeriods,
                                )
                            }
                    }
                }, onSuccess = ::afterPeriodSave)
            },
            onRequestDelete = { periodDeleteId = it },
            onDeletePeriod = { id, version ->
                val original = state.periods.singleOrNull { it.id == id }
                controller.mutate("DELETE", "/v1/unavailability/$id?expectedVersion=$version", onSuccess = {
                    if (original != null) {
                        undo =
                            WorkspaceUndo("일정 삭제 되돌리기") {
                                val inverse =
                                    UnavailabilityCreateRequest(
                                        original.type,
                                        original.startsAtEpochMillis,
                                        original.endsAtEpochMillis,
                                        original.reason,
                                        original.userId,
                                    )
                                controller.mutate(
                                    "POST",
                                    "/v1/unavailability",
                                    workspaceJson.encodeToString(inverse),
                                    onSuccess = ::reloadPeriods,
                                )
                            }
                    }
                    afterPeriodSave()
                })
            },
        )
    val productCallbacks =
        ProductCallbacks(
            onSelectProduct = controller::selectProduct,
            onBrowseTasks = { product ->
                controller.filterTasks(TaskFilters(productId = product))
                appState.navigate(AxiaDestination.Tasks)
            },
            onRefresh = { controller.selectProduct(state.selectedProduct) },
            onAddRepository = { name ->
                controller.mutate(
                    "POST",
                    "/v1/products/${state.selectedProduct.name}/repositories",
                    workspaceJson.encodeToString(
                        AddRepositoryRequest(name),
                    ),
                    onSuccess = {
                        controller.selectProduct(state.selectedProduct)
                    },
                )
            },
            onAddProject = { number ->
                controller.mutate(
                    "POST",
                    "/v1/products/${state.selectedProduct.name}/projects",
                    workspaceJson.encodeToString(
                        AddProjectRequest(number),
                    ),
                    onSuccess = {
                        controller.selectProduct(state.selectedProduct)
                    },
                )
            },
            onRepositoryEnabled = {
                id,
                enabled,
                ->
                controller.mutate(
                    "PATCH",
                    "/v1/products/${state.selectedProduct.name}/repositories/$id",
                    workspaceJson.encodeToString(
                        UpdateIntegrationRequest(enabled),
                    ),
                    onSuccess = {
                        controller.selectProduct(state.selectedProduct)
                    },
                )
            },
            onProjectEnabled = {
                id,
                enabled,
                ->
                controller.mutate(
                    "PATCH",
                    "/v1/products/${state.selectedProduct.name}/projects/$id",
                    workspaceJson.encodeToString(
                        UpdateIntegrationRequest(enabled),
                    ),
                    onSuccess = {
                        controller.selectProduct(state.selectedProduct)
                    },
                )
            },
            onDefaultProject = {
                id,
                projectId,
                ->
                controller.mutate(
                    "PUT",
                    "/v1/repositories/$id/default-project",
                    workspaceJson.encodeToString(
                        DefaultProjectRequest(projectId),
                    ),
                    onSuccess = {
                        controller.selectProduct(state.selectedProduct)
                    },
                )
            },
            onOpenFieldCatalog = controller::loadFieldCatalog,
            onSaveMappings = {
                id,
                mappings,
                ->
                controller.mutate(
                    "PUT",
                    "/v1/projects/$id/field-mappings",
                    workspaceJson.encodeToString(
                        ProjectFieldMappingRequest(mappings),
                    ),
                    onSuccess = {
                        controller.selectProduct(state.selectedProduct)
                    },
                )
            },
            onOpenUrl = uri::openUri,
        )

    AxiaAppShell(appState, state.dashboard(), themeMode, onThemeModeChange) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
            if (state.pendingTaskWrite !=
                null
            ) {
                AxiaBanner(
                    "GitHub 변경 결과 확인 필요",
                    description =
                        "중복 변경을 막기 위해 새 쓰기를 잠시 멈췄습니다. " +
                            "결과 확인은 이전 작업을 다시 실행하지 않습니다.",
                    style = AxiaBannerStyle.Warning,
                    actionLabel = if (state.busy) "확인 중…" else "결과 확인",
                    onAction = controller::confirmPendingTaskWrite,
                    announceChanges = true,
                )
            }
            state.error?.let {
                AxiaBanner(
                    "요청을 완료하지 못했어요",
                    description = it,
                    style = AxiaBannerStyle.Danger,
                    actionLabel = "확인",
                    onAction = controller::dismissMessage,
                    announceChanges = true,
                )
            }
            if (state.error ==
                null
            ) {
                state.notice?.let {
                    AxiaBanner(
                        it,
                        style =
                            if (state.pendingTaskWrite ==
                                null
                            ) {
                                AxiaBannerStyle.Success
                            } else {
                                AxiaBannerStyle.Info
                            },
                        actionLabel = "확인",
                        onAction = controller::dismissMessage,
                    )
                }
            }
            undo?.let { action ->
                AxiaBanner(action.label, actionLabel = "되돌리기", onAction = {
                    if (!state.busy &&
                        (action.target != WorkspaceUndoTarget.GitHub || state.pendingTaskWrite == null)
                    ) {
                        undo = null
                        action.execute()
                    }
                })
            }
            when (appState.currentDestination) {
                AxiaDestination.Dashboard -> {
                    DashboardScreen(state.dashboard(), onBrowseTasks = {
                        controller.filterTasks(TaskFilters(userId = state.currentUser?.member?.id))
                        appState.navigate(AxiaDestination.Tasks)
                    }, onTaskSelected = { key ->
                        val parts = key.split(':', limit = 3)
                        parts.firstOrNull()?.toLongOrNull()?.let { id ->
                            appState.navigate(AxiaDestination.Tasks)
                            controller.selectTask(
                                id,
                                parts.getOrNull(2)?.takeIf {
                                    parts.getOrNull(1) == TaskKind.REVIEW_REQUEST.name &&
                                        it.isNotBlank()
                                },
                            )
                        }
                    })
                }

                AxiaDestination.Tasks -> {
                    TasksScreen(
                        state.tasks,
                        state.members,
                        state.filters,
                        state.selectedTask,
                        state.loading,
                        state.detailLoading,
                        controller::filterTasks,
                        { task -> controller.selectTask(task.id, task.assignmentKey) },
                        controller::refresh,
                        uri::openUri,
                        detailActions = { detail ->
                            TaskDetailActions(
                                detail,
                                state.github.readOnly,
                                state.busy || state.pendingTaskWrite != null,
                                ::openTaskWrite,
                                onReminderOverride = { hours ->
                                    controller.mutate(
                                        "PUT",
                                        "/v1/tasks/${detail.task.id}/reminder-override",
                                        workspaceJson.encodeToString(
                                            ReminderOverrideRequest(hours, detail.task.assignmentKey),
                                        ),
                                        onSuccess = {
                                            controller.selectTask(detail.task.id, detail.task.assignmentKey)
                                        },
                                    )
                                },
                                onReminderSuppression = { suppressed ->
                                    controller.mutate(
                                        "PUT",
                                        "/v1/tasks/${detail.task.id}/reminder-suppression",
                                        workspaceJson.encodeToString(
                                            ReminderSuppressionRequest(suppressed, detail.task.assignmentKey),
                                        ),
                                        onSuccess = {
                                            controller.selectTask(detail.task.id, detail.task.assignmentKey)
                                        },
                                    )
                                },
                            )
                        },
                        createActions = {
                            val mayCreate =
                                capabilities?.writableProducts?.isNotEmpty() == true && !state.github.readOnly && !state.busy &&
                                    state.pendingTaskWrite == null
                            AxiaButton("Issue 만들기", { openTaskWrite(TaskWriteMode.CREATE_ISSUE) }, enabled = mayCreate)
                            AxiaButton(
                                "Draft 만들기",
                                { openTaskWrite(TaskWriteMode.CREATE_DRAFT) },
                                enabled = mayCreate,
                                style = AxiaButtonStyle.Secondary,
                            )
                            AxiaButton("완료 이력", { openHistory(null) }, style = AxiaButtonStyle.Tertiary)
                        },
                    )
                }

                AxiaDestination.Products -> {
                    ProductsScreen(
                        state.products,
                        state.selectedProduct,
                        state.integrations,
                        state.fieldCatalog,
                        state.selectedProduct in capabilities?.managedIntegrationProducts.orEmpty(),
                        state.detailLoading,
                        state.busy,
                        productCallbacks,
                    )
                }

                AxiaDestination.Members -> {
                    if (showingHistory) {
                        AxiaButton("멤버 목록으로", { showingHistory = false }, style = AxiaButtonStyle.Tertiary)
                        CompletionHistoryScreen(
                            CompletionHistoryUiState(
                                state.history,
                                state.members,
                                state.historyUserId,
                                state.historyProductId,
                                state.historyCursor,
                                state.historyLoading,
                            ),
                            CompletionHistoryCallbacks(
                                onFilterUser = { controller.loadHistory(userId = it) },
                                onFilterProduct = { controller.loadHistory(productId = it) },
                                onOpenTask = ::openTask,
                                onOpenMember = ::openMember,
                                onLoadMore = {
                                    controller.loadHistory(more = true)
                                },
                                onRefresh = { controller.loadHistory() },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        MembersScreen(
                            MembersUiState(
                                state.currentUser,
                                state.members,
                                memberSearch,
                                selectedMemberId,
                                state.selectedMember,
                                memberEditor,
                                memberTab,
                                state.loading || state.detailLoading,
                                state.busy,
                            ),
                            memberCallbacks,
                            Modifier.weight(1f),
                        )
                    }
                }

                AxiaDestination.Calendar -> {
                    CalendarScreen(
                        month,
                        state.calendarTasks,
                        calendarFilters,
                        UnavailabilityUiState(
                            state.currentUser,
                            state.members,
                            state.periods,
                            periodUserId,
                            periodEditor,
                            periodDeleteId,
                            state.periodsLoading,
                            state.busy,
                        ),
                        CalendarCallbacks(
                            onMonthChange = { month = it },
                            onFiltersChange = {
                                calendarFilters = it
                                periodUserId = it.userId
                            },
                            onOpenTask = ::openTask,
                            onCreatePeriodRange = { user, from, until ->
                                periodEditor =
                                    UnavailabilityEditorDraft(userId = user, startsAt = "$from 00:00", endsAt = "$until 00:00")
                            },
                            onMovePeriod = {
                                period,
                                from,
                                until,
                                ->
                                periodCallbacks.onUpdatePeriod(period.id, movedPeriod(period, from, until))
                            },
                            onMoveTarget = { task, project, date ->
                                val command = calendarTargetCommand(task, project, date.toString())
                                controller.submitTaskWrite(command) { result ->
                                    val latest = gateway.get<TaskDetail>("/v1/tasks/${result.taskId ?: task.id}")
                                    val after = latest.task.projects.singleOrNull { it.projectId == project.projectId }
                                    // Capture the confirmed field values and their revision. Never combine an old
                                    // inverse with a newly fetched revision when the user later presses Undo.
                                    if (after == project.copy(targetDate = date.toString()) && latest.task.canWrite) {
                                        undo =
                                            WorkspaceUndo("마감일 변경 되돌리기", WorkspaceUndoTarget.GitHub) {
                                                controller.submitTaskWrite(
                                                    calendarTargetCommand(latest.task, after, project.targetDate),
                                                ) { reloadPeriods() }
                                            }
                                    }
                                    reloadPeriods()
                                }
                            },
                        ),
                        periodCallbacks,
                        Modifier.weight(1f),
                        readOnly = state.github.readOnly,
                        taskWritePending =
                            state.pendingTaskWrite != null,
                    )
                }

                AxiaDestination.Analytics -> {
                    if (flags.canViewAnalytics) {
                        AnalyticsScreen(
                            AnalyticsUiState(
                                state.currentUser,
                                analyticsFilter,
                                state.completionAnalytics,
                                state.memberAnalytics,
                                state.operationsAnalytics,
                                state.analyticsLoading,
                            ),
                            AnalyticsCallbacks({ analyticsFilter = it }, {
                                analyticsFilter = it
                                controller.loadAnalytics(it.query())
                            }, { if (analyticsFilter.isValid) controller.loadAnalytics(analyticsFilter.query()) }, ::openMember),
                            Modifier.weight(1f),
                        )
                    } else {
                        AxiaEmptyState("분석 권한이 없어요", "현재 조직 역할에 따라 분석 화면이 제공됩니다.")
                    }
                }

                AxiaDestination.Notifications -> {
                    NotificationCenterScreen(
                        NotificationUiState(
                            state.notifications,
                            state.unreadCount,
                            state.unreadOnly,
                            state.notificationCursor,
                            state.inboxLoading,
                            state.busy,
                        ),
                        NotificationCallbacks(
                            onRefresh = { controller.loadInbox() },
                            onUnreadOnlyChange = { controller.loadInbox(unreadOnly = it) },
                            onMarkRead = { id ->
                                controller.mutate("POST", "/v1/notifications/$id/read", onSuccess = { controller.loadInbox() })
                            },
                            onMarkAllRead = {
                                controller.mutate(
                                    "POST",
                                    "/v1/notifications/read-all",
                                    onSuccess = { controller.loadInbox() },
                                )
                            },
                            onOpenTask = ::openTask,
                            onOpenMember = ::openMember,
                            onOpenUnavailability = ::openPeriods,
                            onLoadMore = {
                                controller.loadInbox(more = true)
                            },
                        ),
                        Modifier.weight(1f),
                    )
                }

                AxiaDestination.Management -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                        AxiaButton(
                            "구성원 권한",
                            {
                                managementSection = ManagementSection.Members
                                memberTab = MemberDetailTab.Permissions
                            },
                            style =
                                if (managementSection ==
                                    ManagementSection.Members
                                ) {
                                    AxiaButtonStyle.Primary
                                } else {
                                    AxiaButtonStyle.Secondary
                                },
                        )
                        if (flags.canManageIntegrations) {
                            AxiaButton(
                                "제품 연동",
                                {
                                    managementSection = ManagementSection.Integrations
                                    controller.selectProduct(state.selectedProduct)
                                },
                                style =
                                    if (managementSection ==
                                        ManagementSection.Integrations
                                    ) {
                                        AxiaButtonStyle.Primary
                                    } else {
                                        AxiaButtonStyle.Secondary
                                    },
                            )
                            AxiaButton(
                                "리마인드 정책",
                                {
                                    managementSection = ManagementSection.Reminders
                                    controller.loadReminderPolicies()
                                },
                                style =
                                    if (managementSection ==
                                        ManagementSection.Reminders
                                    ) {
                                        AxiaButtonStyle.Primary
                                    } else {
                                        AxiaButtonStyle.Secondary
                                    },
                            )
                        }
                    }
                    if (managementSection == ManagementSection.Integrations &&
                        flags.canManageIntegrations
                    ) {
                        ProductsScreen(
                            state.products,
                            state.selectedProduct,
                            state.integrations,
                            state.fieldCatalog,
                            state.selectedProduct in capabilities?.managedIntegrationProducts.orEmpty(),
                            state.detailLoading,
                            state.busy,
                            productCallbacks,
                            Modifier.weight(1f),
                        )
                    } else if (managementSection == ManagementSection.Reminders && flags.canManageIntegrations) {
                        ReminderPolicyScreen(
                            ReminderPolicyUiState(
                                state.currentUser,
                                state.reminderProjects,
                                state.globalReminderPolicy,
                                state.boardReminderPolicies,
                                state.selectedReminderProjectId,
                                state.reminderPoliciesLoading,
                                state.busy,
                            ),
                            ReminderPolicyCallbacks(
                                onSelectProject = { controller.loadReminderPolicies(it) },
                                onRefresh = { controller.loadReminderPolicies() },
                                onSaveGlobal = { policy ->
                                    controller.mutate(
                                        "PUT",
                                        "/v1/reminder-policy",
                                        workspaceJson.encodeToString(policy),
                                        onSuccess = { controller.loadReminderPolicies() },
                                    )
                                },
                                onSaveBoard = {
                                    id,
                                    policy,
                                    ->
                                    controller.mutate(
                                        "PUT",
                                        "/v1/projects/$id/reminder-policy",
                                        workspaceJson.encodeToString(
                                            policy,
                                        ),
                                        onSuccess = {
                                            controller.loadReminderPolicies(id)
                                        },
                                    )
                                },
                            ),
                            Modifier.weight(1f),
                        )
                    } else {
                        MembersScreen(
                            MembersUiState(
                                state.currentUser,
                                state.members,
                                memberSearch,
                                selectedMemberId,
                                state.selectedMember,
                                memberEditor,
                                MemberDetailTab.Permissions,
                                state.loading || state.detailLoading,
                                state.busy,
                            ),
                            memberCallbacks,
                            Modifier.weight(1f),
                        )
                    }
                }

                AxiaDestination.Settings -> {
                    SettingsScreen(
                        themeMode,
                        onThemeModeChange,
                        onManageSessions = onManageSessions,
                        onLogout = onLogout,
                        onCheckUpdates = onCheckUpdates,
                        updateStatusMessage = updateStatusMessage,
                    )
                }
            }
        }
    }
    taskWriteMode?.let { mode ->
        TaskWriteDialog(
            mode,
            state.selectedTask.takeUnless { mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CREATE_DRAFT) },
            state.currentUser,
            state.members,
            state.selectedProduct,
            state.integrations,
            state.detailLoading,
            state.busy,
            state.pendingTaskWrite,
            state.error,
            controller::selectProduct,
            { controller.submitTaskWrite(it) },
            controller::confirmPendingTaskWrite,
            { taskWriteMode = null },
            readOnly = state.github.readOnly,
        )
    }
}
