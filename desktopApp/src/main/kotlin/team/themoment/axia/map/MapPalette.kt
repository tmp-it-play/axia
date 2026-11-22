package team.themoment.axia.map

import androidx.compose.ui.graphics.Color

object MapPalette {
    val ocean = Color(0xFF2C4A5E)
    val unclaimed = Color(0xFFDCE3E6)
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
    val label = Color(0xFF2A2119)
    val labelHalo = Color(0xB3F2EAD8)

    /** 클수록 부유함. */
    val wealth =
        listOf(
            Color(0xFFE9E4CF),
            Color(0xFFC9D3B0),
            Color(0xFFA2BE93),
            Color(0xFF74A07F),
            Color(0xFF4B7F6E),
            Color(0xFF2E5A57),
        )

    /** 클수록 나쁨. */
    val severity =
        listOf(
            Color(0xFFEFE3C2),
            Color(0xFFE1C78E),
            Color(0xFFCFA45E),
            Color(0xFFB87A3D),
            Color(0xFF925229),
            Color(0xFF64321A),
        )

    /** 음수는 붉은 쪽, 양수는 푸른 쪽. */
    val diverging =
        listOf(
            Color(0xFF9E3A2E),
            Color(0xFFD08A6A),
            Color(0xFFE8DCC0),
            Color(0xFFB5C98F),
            Color(0xFF7FA764),
            Color(0xFF4E7A42),
        )
}
