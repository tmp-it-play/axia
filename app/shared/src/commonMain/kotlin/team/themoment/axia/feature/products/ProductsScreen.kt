package team.themoment.axia.feature.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.domain.ProductId
import team.themoment.axia.products.IntegrationAccess
import team.themoment.axia.products.IntegrationListResponse
import team.themoment.axia.products.ProductSummary
import team.themoment.axia.products.ProjectFieldCatalog
import team.themoment.axia.products.ProjectFieldMapping
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.products.ProjectSemanticField

data class ProductCallbacks(
    val onSelectProduct: (ProductId) -> Unit,
    val onBrowseTasks: (ProductId) -> Unit,
    val onRefresh: () -> Unit,
    val onAddRepository: (String) -> Unit,
    val onAddProject: (Int) -> Unit,
    val onRepositoryEnabled: (Long, Boolean) -> Unit,
    val onProjectEnabled: (Long, Boolean) -> Unit,
    val onDefaultProject: (Long, Long?) -> Unit,
    val onOpenFieldCatalog: (Long) -> Unit,
    val onSaveMappings: (Long, List<ProjectFieldMapping>) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

@Composable
fun ProductsScreen(
    products: List<ProductSummary>,
    selectedProduct: ProductId,
    integrations: IntegrationListResponse?,
    fieldCatalog: ProjectFieldCatalog?,
    canManage: Boolean,
    loading: Boolean,
    saving: Boolean,
    callbacks: ProductCallbacks,
    modifier: Modifier = Modifier,
) {
    var newRepository by remember(selectedProduct) { mutableStateOf("") }
    var newProject by remember(selectedProduct) { mutableStateOf("") }
    var editingProjectId by remember(selectedProduct) { mutableStateOf<Long?>(null) }
    val overview = products.firstOrNull { it.id == selectedProduct }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
        ) {
            ProductId.entries.forEach { product ->
                AxiaButton(
                    product.displayName,
                    { callbacks.onSelectProduct(product) },
                    style =
                        if (product ==
                            selectedProduct
                        ) {
                            AxiaButtonStyle.Primary
                        } else {
                            AxiaButtonStyle.Secondary
                        },
                )
            }
        }
        AxiaPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                    Text(
                        selectedProduct.displayName,
                        style = AxiaTheme.typography.sectionTitle,
                        color = AxiaTheme.semanticColors.textPrimary,
                    )
                    Text(
                        if (overview ==
                            null
                        ) {
                            "작업 현황을 불러오고 있습니다."
                        } else {
                            "진행 중 ${overview.openTasks} · 지연 ${overview.overdueTasks} · 미할당 ${overview.unassignedTasks}"
                        },
                        style = AxiaTheme.typography.body,
                        color = AxiaTheme.semanticColors.textSecondary,
                    )
                }
                AxiaButton("제품 태스크 보기", { callbacks.onBrowseTasks(selectedProduct) }, style = AxiaButtonStyle.Secondary)
            }
        }
        if (integrations == null) {
            AxiaEmptyState(if (loading) "연결 상태를 불러오고 있어요" else "연결 상태를 확인하지 못했어요", "새로고침으로 다시 확인할 수 있습니다.")
            AxiaButton("새로고침", callbacks.onRefresh, enabled = !loading, style = AxiaButtonStyle.Secondary)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("GitHub 저장소", style = AxiaTheme.typography.sectionTitle, color = AxiaTheme.semanticColors.textPrimary)
                if (canManage) {
                    AxiaButton(
                        "GitHub App 접근 설정",
                        { callbacks.onOpenUrl(integrations.appSettingsUrl) },
                        style = AxiaButtonStyle.Tertiary,
                    )
                }
            }
            integrations.repositories.forEach { repository ->
                AxiaPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(repository.name, style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
                            AccessBadge(repository.access, repository.enabled)
                            AxiaButton("GitHub에서 열기", {
                                callbacks.onOpenUrl("https://github.com/${repository.owner}/${repository.name}")
                            }, style = AxiaButtonStyle.Tertiary)
                            if (canManage) {
                                AxiaButton(if (repository.enabled) "연결 비활성화" else "접근 확인 후 활성화", {
                                    callbacks.onRepositoryEnabled(repository.id, !repository.enabled)
                                }, enabled = !saving, style = AxiaButtonStyle.Secondary)
                            }
                        }
                        AxiaSelect(
                            "기본 보드",
                            repository.defaultProjectId,
                            listOf(AxiaSelectOption<Long?>(null, "기본 보드 없음")) +
                                integrations.projects.map { AxiaSelectOption<Long?>(it.id, "#${it.number} · ${it.title ?: "이름 확인 전"}") },
                            onChange = { callbacks.onDefaultProject(repository.id, it) },
                            enabled =
                                canManage && !saving,
                        )
                    }
                }
            }
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3), verticalAlignment = Alignment.Bottom) {
                    AxiaTextField(newRepository, { newRepository = it }, "저장소 추가", Modifier.weight(1f), placeholder = "같은 조직의 저장소 이름")
                    AxiaButton(
                        "접근 확인 및 추가",
                        { callbacks.onAddRepository(newRepository.trim()) },
                        enabled =
                            !saving && newRepository.trim().matches(Regex("[A-Za-z0-9_.-]{1,100}")),
                    )
                }
            }
            AxiaDivider()
            Text("공유 Projects 보드", style = AxiaTheme.typography.sectionTitle, color = AxiaTheme.semanticColors.textPrimary)
            integrations.projects.forEach { project ->
                AxiaPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "#${project.number} · ${project.title ?: "연결 대기"}",
                                style = AxiaTheme.typography.bodyEmphasis,
                                color = AxiaTheme.semanticColors.textPrimary,
                            )
                            AccessBadge(project.access, project.enabled)
                            if (canManage) {
                                AxiaButton(if (project.enabled) "보드 비활성화" else "접근 확인 후 활성화", {
                                    callbacks.onProjectEnabled(project.id, !project.enabled)
                                }, enabled = !saving, style = AxiaButtonStyle.Secondary)
                                AxiaButton("필드 매핑", {
                                    editingProjectId = project.id
                                    callbacks.onOpenFieldCatalog(project.id)
                                }, enabled = !saving, style = AxiaButtonStyle.Tertiary)
                            }
                        }
                        if (project.repositoryLinkWarning) {
                            AxiaBanner(
                                "저장소 연결이 없는 보드",
                                description = "Issue·PR 원본 작업은 사용할 수 있지만 보드 연결을 확인해 주세요.",
                                style = AxiaBannerStyle.Warning,
                            )
                        }
                        Text(
                            "${project.fields.size}개 필드를 원본 Node ID로 연결합니다. 보드나 옵션 이름 변경으로 연결이 바뀌지 않습니다.",
                            style = AxiaTheme.typography.secondary,
                            color = AxiaTheme.semanticColors.textSecondary,
                        )
                        if (canManage && editingProjectId == project.id) {
                            if (fieldCatalog?.projectId == project.id) {
                                MappingEditor(project, fieldCatalog, saving, {
                                    callbacks.onSaveMappings(project.id, it)
                                    editingProjectId =
                                        null
                                }, { editingProjectId = null })
                            } else {
                                Text(
                                    "GitHub 필드 목록을 확인하고 있습니다.",
                                    style = AxiaTheme.typography.secondary,
                                    color = AxiaTheme.semanticColors.textSecondary,
                                )
                                AxiaButton("닫기", { editingProjectId = null }, style = AxiaButtonStyle.Tertiary)
                            }
                        }
                    }
                }
            }
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3), verticalAlignment = Alignment.Bottom) {
                    AxiaTextField(newProject, { newProject = it }, "보드 추가", Modifier.weight(1f), placeholder = "운영 보드 번호 (템플릿 #19 제외)")
                    val number = newProject.toIntOrNull()
                    AxiaButton(
                        "접근 확인 및 추가",
                        { number?.let(callbacks.onAddProject) },
                        enabled =
                            !saving && number != null && number > 0 && number != 19,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessBadge(
    access: IntegrationAccess,
    enabled: Boolean,
) {
    AxiaBadge(
        when {
            access == IntegrationAccess.PENDING -> "접근 확인 대기"
            access == IntegrationAccess.UNAVAILABLE -> "접근 불가"
            !enabled -> "비활성"
            else -> "연결됨"
        },
        style = if (enabled) AxiaBadgeStyle.Success else AxiaBadgeStyle.Warning,
    )
}

@Composable
private fun MappingEditor(
    project: ProjectIntegration,
    catalog: ProjectFieldCatalog,
    saving: Boolean,
    onSave: (List<ProjectFieldMapping>) -> Unit,
    onCancel: () -> Unit,
) {
    var mappings by remember(project.id, project.fields) {
        mutableStateOf(
            ProjectSemanticField.entries.map { semantic ->
                project.fields.singleOrNull {
                    it.semantic ==
                        semantic
                }
                    ?: ProjectFieldMapping(semantic, "")
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
        mappings.forEach { mapping ->
            val isDate = mapping.semantic == ProjectSemanticField.START_DATE || mapping.semantic == ProjectSemanticField.TARGET_DATE
            val fields = catalog.fields.filter { it.dataType == if (isDate) "DATE" else "SINGLE_SELECT" }
            AxiaSelect(
                mapping.semantic.label,
                mapping.fieldNodeId,
                listOf(AxiaSelectOption("", "필드 선택")) + fields.map { AxiaSelectOption(it.id, it.name) },
                onChange = { id ->
                    mappings =
                        mappings.map {
                            if (it.semantic ==
                                mapping.semantic
                            ) {
                                it.copy(fieldNodeId = id, options = emptyMap())
                            } else {
                                it
                            }
                        }
                },
                enabled = !saving,
            )
            val field = fields.singleOrNull { it.id == mapping.fieldNodeId }
            mapping.semantic.optionKeys.forEach { key ->
                AxiaSelect(
                    "${mapping.semantic.label} · ${key.second}",
                    mapping.options[key.first].orEmpty(),
                    listOf(AxiaSelectOption("", "옵션 선택")) + field?.options.orEmpty().map { AxiaSelectOption(it.id, it.name) },
                    onChange = { id ->
                        mappings =
                            mappings.map {
                                if (it.semantic ==
                                    mapping.semantic
                                ) {
                                    it.copy(options = it.options + (key.first to id))
                                } else {
                                    it
                                }
                            }
                    },
                    enabled =
                        !saving && field != null,
                )
            }
        }
        val valid =
            mappings.all { it.fieldNodeId.isNotBlank() && it.semantic.optionKeys.all { key -> !it.options[key.first].isNullOrBlank() } } &&
                mappings.map { it.fieldNodeId }.distinct().size == 4
        Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
            AxiaButton("매핑 저장", { onSave(mappings) }, enabled = valid && !saving)
            AxiaButton("취소", onCancel, enabled = !saving, style = AxiaButtonStyle.Tertiary)
        }
    }
}

private val ProjectSemanticField.label: String get() =
    when (this) {
        ProjectSemanticField.STATUS -> "진행 상태"
        ProjectSemanticField.PRIORITY -> "우선순위"
        ProjectSemanticField.START_DATE -> "시작일"
        ProjectSemanticField.TARGET_DATE -> "마감일"
    }
private val ProjectSemanticField.optionKeys: List<Pair<String, String>> get() =
    when (this) {
        ProjectSemanticField.STATUS -> listOf("TODO" to "할 일", "IN_PROGRESS" to "진행 중", "DONE" to "완료")
        ProjectSemanticField.PRIORITY -> listOf("HIGH" to "높음", "MEDIUM" to "보통", "LOW" to "낮음", "IN_REVIEW" to "리뷰 중")
        else -> emptyList()
    }
