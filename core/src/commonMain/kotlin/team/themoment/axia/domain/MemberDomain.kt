package team.themoment.axia.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ProductId(
    val displayName: String,
) {
    HELLO_GSM("Hello,GSM"),
    DATA_GSM("DataGSM"),
    EVERY_GSM("EveryGSM"),
    READY_GSM("Ready,GSM"),
}

@Serializable
enum class TechnicalPosition(
    val displayName: String,
) {
    SERVER("Server"),
    FRONTEND("Frontend"),
    CLOUD("Cloud"),
    DESIGN("Design"),
}

@Serializable
enum class OrganizationRole(
    val displayName: String,
    val headPosition: TechnicalPosition? = null,
    val product: ProductId? = null,
) {
    TEAM_LEADER("Team Leader"),
    SERVER_HEAD("Server Head", headPosition = TechnicalPosition.SERVER),
    CLOUD_HEAD("Cloud Head", headPosition = TechnicalPosition.CLOUD),
    FRONT_HEAD("Front Head", headPosition = TechnicalPosition.FRONTEND),
    DESIGN_HEAD("Design Head", headPosition = TechnicalPosition.DESIGN),
    C_LEADER("C-Leader"),
    HELLO_GSM_PO("Hello,GSM-PO", product = ProductId.HELLO_GSM),
    DATA_GSM_PO("DataGSM-PO", product = ProductId.DATA_GSM),
    EVERY_GSM_PO("EveryGSM-PO", product = ProductId.EVERY_GSM),
    READY_GSM_PO("Ready,GSM-PO", product = ProductId.READY_GSM),
}
