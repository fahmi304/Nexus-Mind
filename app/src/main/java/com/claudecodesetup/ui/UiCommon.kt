package com.claudecodesetup.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.claudecodesetup.R

val GoogleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val DmSansFamily = FontFamily(
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
)

val SpaceMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("Space Mono"), fontProvider = GoogleFontProvider),
    Font(googleFont = GoogleFont("Space Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
)

val SyneFamily = FontFamily(
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.ExtraBold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
)

/** Draws a blurred glow shadow behind the composable using BlurMaskFilter. */
fun Modifier.glowShadow(color: Color, blurRadius: Dp, cornerRadius: Dp): Modifier =
    this.drawBehind {
        if (color.alpha < 0.01f) return@drawBehind
        drawIntoCanvas { canvas ->
            val paint = Paint()
            paint.asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                maskFilter = BlurMaskFilter(blurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
                this.color = color.toArgb()
            }
            canvas.drawRoundRect(
                left = 0f, top = 0f,
                right = size.width, bottom = size.height,
                radiusX = cornerRadius.toPx(), radiusY = cornerRadius.toPx(),
                paint = paint
            )
        }
    }

// Design system color tokens
val NexusBg      = Color(0xFF0C0C0F)
val NexusSurface = Color(0xFF151518)
val NexusSurface2 = Color(0xFF1E1E22)
val NexusBorder  = Color(0xFF2A2A30)
val NexusBorder2 = Color(0xFF3A3A42)
val NexusAccent  = Color(0xFFE8834A)   // amber — primary
val NexusAccentDim = Color(0x22E8834A)
val NexusGreen   = Color(0xFF3DD68C)
val NexusGreenDim = Color(0x183DD68C)
val NexusBlue    = Color(0xFF60A5FA)
val NexusAmber   = Color(0xFFFBBF24)
val NexusRed     = Color(0xFFF87171)
val NexusText    = Color(0xFFF0F0F2)
val NexusText2   = Color(0xFF9090A0)
val NexusText3   = Color(0xFF60606E)

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBg)
    ) {
        content()
    }
}
