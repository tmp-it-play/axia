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
}
