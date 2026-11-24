package team.themoment.axia.simulation.politics

import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

data class SeimasResolution(
    val date: LocalDate,
    val vote: RollCall,
) {
    val report: String
        get() = "조기 총선 결의 ${if (vote.passes) "가결" else "부결"} · 찬성 ${vote.yes}표 / 가결 ${vote.required}표"
}

/** 세이마스 선거법 제167조. 1차 투표에서 명부와 지역구 당선자를 확정하고 미결 지역구의 후보만 결선에 올린다. */
internal object LithuanianSeimas {
    /** 여당이 제안한 조기 총선에 대한 의원단별 찬성 표를 모의한다. 가결선은 법정 정원 141석의 3/5다. */
    fun resolution(
        chamber: Legislature,
        approval: Double,
        date: LocalDate,
    ): SeimasResolution {
        val support = approval / 100.0
        val rows =
            chamber.groups.map { party ->
                val fraction =
                    when (party.role) {
                        PartyRole.LEAD -> 1.0
                        PartyRole.COALITION, PartyRole.SUPPLY -> 0.35 + 0.6 * support
                        PartyRole.OPPOSITION -> 0.95 - support
                        PartyRole.OTHER -> 0.25
                    }.coerceIn(0.0, 1.0)
                PartyVote(party, (party.votingSeats * fraction).roundToInt(), 0)
            }
        return SeimasResolution(date, RollCall(rows, 85, null))
    }

    data class Ballot(
        val district: String,
        /** 1차 득표순. 한 명이면 당선, 두 명 이상이면 결선, 빈 경우에는 선거 불성립이다. */
        val candidates: Map<String, Double>,
    )

    data class Count(
        val vote: Map<String, Double>,
        val listSeats: Map<String, Int>,
        val listVote: Map<String, Double>,
        val mandates: List<RankedMandateList>,
        val firstApproval: Double,
        val districts: Map<MixedArea, List<Ballot>>,
        val modelNames: Map<String, String>,
    ) {
        fun runoff(first: LocalDate): LegislativeRunoff? {
            val ballots = districts.values.flatten()
            val contested = ballots.count { it.candidates.size > 1 }
            if (contested == 0) return null
            return LegislativeRunoff(
                first,
                first.plusWeeks(2),
                listSeats.values.sum() + ballots.count { it.candidates.size == 1 },
                contested,
                listSeats,
                ballots.associate { it.district to it.candidates.keys.toList() },
            )
        }

        fun result(
            salt: Int = 0,
            incumbent: String? = null,
            approval: Double = firstApproval,
        ): LegislativeSeats {
            val returns =
                districts.map { (area, ballots) ->
                    val winners =
                        ballots.map { ballot ->
                            ballot.candidates
                                .maxByOrNull { (party, weight) ->
                                    val campaign =
                                        if (party ==
                                            incumbent
                                        ) {
                                            (1 + (approval - firstApproval) / 100).coerceAtLeast(0.1)
                                        } else {
                                            1.0
                                        }
                                    val participation =
                                        if (salt ==
                                            0
                                        ) {
                                            1.0
                                        } else {
                                            0.96 +
                                                0.08 * DistrictElectorate.localAffinity(ballot.district, salt, modelNames.getValue(party))
                                        }
                                    weight * campaign * participation
                                }?.key
                                .orEmpty()
                        }
                    DistrictResult(area.id, area.name, winners, ballots.map { it.district })
                }
            val local =
                returns
                    .flatMap { it.winners }
                    .filter { it.isNotEmpty() }
                    .groupingBy { it }
                    .eachCount()
            return LegislativeSeats(
                vote.keys.associateWith { (local[it] ?: 0) + (listSeats[it] ?: 0) },
                districts = returns,
                districtMandates = local,
                listSeats = mapOf("전국 명부" to listSeats),
                listVoteShares = listVote,
                rankedLists = mandates,
            )
        }

        /** 후보가 둘 이하이고 1차 기준을 채우지 못하면 일반 결선이 아닌 재선거로 공석을 채운다. */
        fun vacancies(date: LocalDate): List<SeatVacancy> =
            districts.flatMap { (area, ballots) ->
                ballots.mapIndexedNotNull { index, ballot ->
                    if (ballot.candidates.isNotEmpty()) {
                        null
                    } else {
                        SeatVacancy(
                            null,
                            Replacement.DISTRICT,
                            date.plusWeeks(8),
                            districtSeat = true,
                            region = ballot.district,
                            regionId = area.id,
                            districtIndex = index,
                        )
                    }
                }
            }

        fun renamed(rename: PartyRename): Count =
            copy(
                vote = vote.mapKeys { rename.label(it.key) },
                listSeats = listSeats.mapKeys { rename.label(it.key) },
                listVote = listVote.mapKeys { rename.label(it.key) },
                mandates = mandates.map { it.renamed(rename) },
                districts =
                    districts.mapValues { (_, ballots) ->
                        ballots.map { it.copy(candidates = it.candidates.mapKeys { entry -> rename.label(entry.key) }) }
                    },
                modelNames = modelNames.mapKeys { rename.label(it.key) },
            )
    }

    fun count(
        votes: MixedVotes,
        registration: ListRegistration?,
    ): Count {
        val parties = votes.shares.filter { (party, share) -> !votes.independent(party) && share > 0 }
        val total = parties.values.sum()
        val lists = registration?.lists ?: JointListNominations.defaults(MixedSystem.LTU, votes)
        val nationalBallot = parties.mapValues { it.value / total }
        val national = JointListNominations.lithuanianCount(nationalBallot, lists)
        val listVote = lists.associate { list -> list.name to list.members.sumOf { nationalBallot[it] ?: 0.0 } }
        val districts =
            MixedMemberElections.lithuanianAreas.associateWith { area ->
                val weights = votes.weights(area)
                area.labels.mapIndexed { index, name ->
                    val ranking =
                        weights
                            .filterValues { it > 0 }
                            .mapValues { (party, share) ->
                                share.pow(1.9) / -ln(votes.affinity(area.id, index, party))
                            }.entries
                            .sortedByDescending { it.value }
                    val turnout = 0.32 + 0.42 * DistrictElectorate.localAffinity(area.id, index, "투표율")
                    val first = ranking.first()
                    val fraction = first.value / ranking.sumOf { it.value }
                    val candidates =
                        when {
                            fraction > 0.5 && fraction * turnout >= 0.2 -> {
                                mapOf(first.key to first.value)
                            }

                            ranking.size <= 2 -> {
                                emptyMap()
                            }

                            else -> {
                                val tied = ranking.takeWhile { it.value == first.value }
                                val finalists = if (tied.size > 2) tied else ranking.take(2)
                                val support = finalists.associate { it.key to it.value }.toMutableMap()
                                val rest = ranking.drop(finalists.size)
                                for ((party, points) in rest) {
                                    val preferences =
                                        finalists.associate {
                                            it.key to
                                                votes.affinity(name, 100, "${votes.name(party)}·${votes.name(it.key)}")
                                        }
                                    val full = preferences.values.sum()
                                    for ((candidate, weight) in preferences) {
                                        support[candidate] =
                                            support.getValue(candidate) + points * weight / full
                                    }
                                }
                                support
                            }
                        }
                    Ballot(name, candidates)
                }
            }
        return Count(
            votes.shares,
            national.seats,
            listVote,
            national.mandates,
            votes.approval,
            districts,
            votes.shares.keys.associateWith(votes::name),
        )
    }
}
