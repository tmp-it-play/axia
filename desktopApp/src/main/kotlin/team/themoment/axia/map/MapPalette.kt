package team.themoment.axia.map

import androidx.compose.ui.graphics.Color

object MapPalette {
    val ocean = Color(0xFF2C4A5E)
    val unclaimed = Color(0xFFADB9BD)

    /** 지도 모드에 자료가 없는 나라. */
    val noData = Color(0xFFA49F96)
    val peace = Color(0xFFA39A88)
    val countries =
        listOf(
            Color(0xFFC8B48A),
            Color(0xFFA7BC8C),
            Color(0xFFD4A57C),
            Color(0xFFB5A4C8),
            Color(0xFF8FB5AF),
            Color(0xFFD8C77E),
            Color(0xFFC99A98),
            Color(0xFF9CB0CC),
        )
    val coast = Color(0xFF1B2A36)
    val countryBorder = Color(0xFF2A2119)
    val regionBorder = Color(0x592A2119)
    val selectedCountry = Color(0x2EFFFFFF)
    val selectedRegion = Color(0x40FFFFFF)
    val selectedOutline = Color(0xFFFFF2C0)
    val countryName = Color.White.copy(alpha = 0.94f)

    /** 클수록 부유함. */
    val wealth =
        listOf(
            Color(0xFFB7B6A2),
            Color(0xFFA2AE92),
            Color(0xFF88A582),
            Color(0xFF6B9476),
            Color(0xFF4B7F6E),
            Color(0xFF2E5A57),
        )

    /** 클수록 나쁨. */
    val severity =
        listOf(
            Color(0xFFBBB093),
            Color(0xFFB9A075),
            Color(0xFFB79153),
            Color(0xFFB87A3D),
            Color(0xFF925229),
            Color(0xFF64321A),
        )

    /** 음수는 적색, 중립은 회갈색, 양수는 녹색이다. */
    val diverging =
        listOf(
            Color(0xFF9E3A2E),
            Color(0xFFD08A6A),
            Color(0xFFB9AE9E),
            Color(0xFF96AA7F),
            Color(0xFF7FA764),
            Color(0xFF4E7A42),
        )
}
