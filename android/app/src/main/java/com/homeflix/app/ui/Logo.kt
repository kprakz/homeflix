package com.homeflix.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.homeflix.app.R
import com.homeflix.app.ui.theme.HomeflixRed

val BebasNeueFamily = FontFamily(
    Font(R.font.bebas_neue, FontWeight.Normal)
)

@Composable
fun HomeflixLogo(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 42.sp,
) {
    Text(
        text = "HOMEFLIX",
        color = HomeflixRed,
        fontFamily = BebasNeueFamily,
        fontWeight = FontWeight.Normal,
        fontSize = fontSize,
        letterSpacing = 1.8.sp,
        modifier = modifier,
    )
}
