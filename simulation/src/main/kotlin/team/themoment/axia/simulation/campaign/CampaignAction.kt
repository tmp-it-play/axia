package team.themoment.axia.simulation.campaign

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.simulation.economy.BankPressure
import team.themoment.axia.simulation.economy.Creditor
import team.themoment.axia.simulation.economy.DebtPlan
import team.themoment.axia.simulation.economy.GovernorMandate
import team.themoment.axia.simulation.military.MilitaryAction
import team.themoment.axia.simulation.military.Mobilization
import team.themoment.axia.simulation.military.ReconstructionEffort
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.RegionalProgram
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.simulation.society.GovernmentAction

/** 실제 게임과 저장 기록 복원이 같은 명령을 실행한다. */
@Serializable
sealed interface CampaignAction {
    fun applyTo(simulation: Simulation)

    @Serializable
    @SerialName("budget.edit")
    data class EditBudget(
        val key: String,
        val target: Double,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            val name = key.substringAfter('/')
            val item =
                when (key.substringBefore('/')) {
                    "tax" -> TaxKind.valueOf(name)
                    "spending" -> SpendingKind.valueOf(name)
                    "regulation" -> RegulationItem.valueOf(name)
                    "institution" -> InstitutionItem.valueOf(name)
                    "country" -> simulation.countryPolicy(name)
                    else -> error("알 수 없는 정책 키입니다: $key")
                }
            require(target.isFinite())
            simulation.editBudgetDraft(item, target)
        }
    }

    @Serializable
    @SerialName("budget.negotiate")
    data class NegotiateBudget(
        val party: String?,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.negotiateBudgetWith(party)
    }

    @Serializable
    @SerialName("budget.clear")
    data object ClearBudget : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.clearBudgetDraft()
    }

    @Serializable
    @SerialName("budget.revise")
    data object ReviseBudget : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.reviseStalledBudget()
    }

    @Serializable
    @SerialName("budget.introduce")
    data object IntroduceBudget : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.introduceBudget()
    }

    @Serializable
    @SerialName("election.list")
    data class ChooseElectionList(
        val partner: String?,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.chooseElectionList(partner)
    }

    @Serializable
    @SerialName("election.coalition")
    data class PreferCoalition(
        val party: String?,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.preferCoalitionPartner(party)
    }

    @Serializable
    @SerialName("election.early")
    data object EarlyElection : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.callEarlyElection()
    }

    @Serializable
    @SerialName("monetary.governor")
    data class AppointGovernor(
        val mandate: GovernorMandate,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.appointGovernor(mandate)
        }
    }

    @Serializable
    @SerialName("monetary.pressure")
    data class PressureBank(
        val direction: BankPressure,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.pressureBank(direction)
        }
    }

    @Serializable
    @SerialName("debt.terms")
    data class CreditorTerms(
        val creditor: Creditor,
        val terms: DebtPlan?,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.setCreditorTerms(creditor, terms)
        }
    }

    @Serializable
    @SerialName("trade.route")
    data class SetTradeRoute(
        val target: String,
        val partner: String,
        val restrict: Boolean,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.setTradeRoute(target, partner, restrict)
        }
    }

    @Serializable
    @SerialName("monetary.rate")
    data class DirectRate(
        val rate: Double,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            require(rate.isFinite())
            simulation.directPolicyRate(rate)
        }
    }

    @Serializable
    @SerialName("organization.act")
    data class ActOnOrganization(
        val id: Int,
        val action: GovernmentAction,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.actOnOrganization(id, action)
        }
    }

    @Serializable
    @SerialName("organization.rename")
    data class RenameOrganization(
        val id: Int,
        val name: String,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.renameOrganization(id, name)
        }
    }

    @Serializable
    @SerialName("party.rename")
    data class RenameParty(
        val country: String,
        val id: Int,
        val name: String,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.renameParty(country, id, name)
    }

    @Serializable
    @SerialName("rebels.rename")
    data class RenameRebels(
        val id: Int,
        val name: String,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) = simulation.renameRebels(id, name)
    }

    @Serializable
    @SerialName("person.rename")
    data class RenamePerson(
        val id: Int,
        val name: String,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.renamePerson(id, name)
        }
    }

    @Serializable
    @SerialName("diplomacy.act")
    data class Diplomatic(
        val target: String,
        val action: DiplomaticAction,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.diplomaticAction(target, action)
        }
    }

    @Serializable
    @SerialName("military.act")
    data class Military(
        val target: String,
        val action: MilitaryAction,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.militaryAction(target, action)
        }
    }

    @Serializable
    @SerialName("military.mobilize")
    data class Mobilize(
        val level: Mobilization,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.setMobilization(level)
        }
    }

    @Serializable
    @SerialName("event.choose")
    data class ChooseEvent(
        val id: Int,
        val index: Int,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.chooseEventOption(id, index)
        }
    }

    @Serializable
    @SerialName("region.program")
    data class SetRegionalProgram(
        val regionId: String,
        val program: RegionalProgram,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.setRegionalProgram(regionId, program)
        }
    }

    @Serializable
    @SerialName("opposition.veto")
    data class SetOppositionVeto(
        val veto: Boolean,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.setOppositionVeto(veto)
        }
    }

    @Serializable
    @SerialName("debt.imf")
    data object RequestImf : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.requestImfProgram()
        }
    }

    @Serializable
    @SerialName("company.rename")
    data class RenameCompany(
        val id: Int,
        val name: String,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.renameCompany(id, name)
        }
    }

    @Serializable
    @SerialName("diplomacy.negotiateRegion")
    data class NegotiateRegion(
        val target: String,
        val regionId: String,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.negotiateRegion(target, regionId)
        }
    }

    @Serializable
    @SerialName("military.reconstruction")
    data class SetReconstructionEffort(
        val effort: ReconstructionEffort,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.setReconstructionEffort(effort)
        }
    }

    @Serializable
    @SerialName("diplomacy.membership")
    data class ChangeMembership(
        val id: String,
        val join: Boolean,
    ) : CampaignAction {
        override fun applyTo(simulation: Simulation) {
            simulation.changeMembership(id, join)
        }
    }
}

internal fun PolicyItem.campaignKey(): String =
    when (this) {
        is TaxKind -> "tax/$name"
        is SpendingKind -> "spending/$name"
        is RegulationItem -> "regulation/$name"
        is InstitutionItem -> "institution/$name"
        is CountryPolicyItem -> "country/$key"
    }
