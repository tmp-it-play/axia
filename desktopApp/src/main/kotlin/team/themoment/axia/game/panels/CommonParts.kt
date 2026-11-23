package team.themoment.axia.game.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.simulation.people.Person
import team.themoment.axia.simulation.politics.WorldNews
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SHORT_DATE = DateTimeFormatter.ofPattern("yyyy.M.d")

/** 패널 안 작은 설명 글. */
@Composable
internal fun Hint(text: String) {
    Text(text, style = DossierType.number(12.sp), color = DossierColors.inkMuted)
}

internal fun shortDate(date: LocalDate): String = SHORT_DATE.format(date)

/** 이름 옆 "이름 바꾸기" 꼬리표와 입력란. 확인하면 [onRename]을 부른다. */
@Composable
internal fun RenameControl(
    key: Any,
    current: String,
    onRename: (String) -> Unit,
) {
    var open by remember(key) { mutableStateOf(false) }
    var draft by remember(key, current) { mutableStateOf(current) }
    PaperTag(if (open) "취소" else "이름 바꾸기", onClick = { open = !open })
    if (open) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(MAX_NAME_LENGTH) },
                singleLine = true,
                textStyle = DossierType.number(13.sp).copy(color = DossierColors.ink),
                modifier =
                    Modifier
                        .weight(1f)
                        .border(1.dp, DossierColors.inkMuted)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            PaperTag("확인", selected = true, enabled = draft.isNotBlank(), onClick = {
                onRename(draft)
                open = false
            })
        }
    }
}

/** 지도자 한 사람. 직함, 이름, 정당, 재임 정보와 이름 바꾸기. */
@Composable
internal fun PersonCard(
    person: Person,
    today: LocalDate,
    onRename: (Int, String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, DossierColors.paperLine)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(person.title, style = DossierType.number(11.sp), color = DossierColors.inkMuted)
        Text(person.name, style = DossierType.title(15.sp), color = DossierColors.ink)
        val party = person.party?.let { "$it · " }.orEmpty()
        // 시작 시점 재임자는 실제 취임일 자료가 없어 게임 시작 때부터 재임 중으로 적는다.
        val tenure = if (person.generated) "${shortDate(person.since)} 취임 · 생성 인물" else "게임 시작 때 재임 중"
        Text(
            "$party${today.year - person.born}세 · $tenure · ${person.termsServed}번째 임기",
            style = DossierType.number(11.sp),
            color = DossierColors.inkMuted,
        )
        RenameControl(person.id, person.name) { onRename(person.id, it) }
    }
}

@Composable
internal fun WorldNewsLine(
    news: WorldNews,
    countryName: (String) -> String,
) {
    val country = countryName(news.countryId)
    // 소식 문구에 나라 이름이 이미 있으면 앞에 다시 붙이지 않는다.
    val prefix = if (country in news.text) "" else "$country · "
    Text(
        text = "${shortDate(news.date)} · $prefix${news.text}",
        style = DossierType.number(11.sp),
        color = if (news.major) DossierColors.ink else DossierColors.inkMuted,
    )
}

/** -100~100 관계 값의 잉크 색. */
internal fun relationColor(value: Double): Color =
    when {
        value >= 30.0 -> DossierColors.positive
        value <= -30.0 -> DossierColors.negative
        else -> DossierColors.ink
    }

internal fun relationLabel(value: Double): String =
    when {
        value >= 70.0 -> "긴밀"
        value >= 40.0 -> "우호"
        value >= 15.0 -> "원만"
        value > -15.0 -> "중립"
        value > -50.0 -> "냉랭"
        else -> "적대"
    }

private const val MAX_NAME_LENGTH = 40
