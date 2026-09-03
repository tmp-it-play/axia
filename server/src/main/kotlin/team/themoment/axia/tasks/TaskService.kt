package team.themoment.axia.tasks

import team.themoment.axia.authorization.AuthorizationService

class TaskService(
    private val authorization: AuthorizationService,
    private val store: TaskStore,
) {
    suspend fun list(
        userId: Long,
        query: TaskQuery,
    ): TaskListResponse = store.list(authorization.requireCurrent(userId), query)

    suspend fun detail(
        userId: Long,
        taskId: Long,
        assignmentKey: String? = null,
    ): TaskDetail = store.detail(authorization.requireCurrent(userId), taskId, assignmentKey)
}
