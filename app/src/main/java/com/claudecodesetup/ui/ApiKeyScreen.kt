package com.claudecodesetup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class KeyStatus { IDLE, LOADING, SUCCESS, ERROR }

@Composable
fun ApiKeyScreen(onSuccess: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(KeyStatus.IDLE) }
    var passwordVisible by remember { mutableStateOf(false) }

    // ── Entry animation ───────────────────────────────────────────────────────
    var entered by remember { mutableStateOf(false) }
    val entryAlpha by animateFloatAsState(if (entered) 1f else 0f,
        tween(500, easing = FastOutSlowInEasing), label = "alpha")
    val entryOffset by animateFloatAsState(if (entered) 0f else 18f,
        tween(500, easing = FastOutSlowInEasing), label = "offset")
    LaunchedEffect(Unit) { entered = true }

    // ── Border / glow colors ──────────────────────────────────────────────────
    val borderColor by animateColorAsState(
        when (status) {
            KeyStatus.IDLE    -> Color(0x1FFFFFFF)
            KeyStatus.LOADING -> Color(0x9960A5FA)
            KeyStatus.SUCCESS -> Color(0xFF10B981)
            KeyStatus.ERROR   -> Color(0xFFEF4444)
        }, tween(250), label = "border"
    )
    val glowColor by animateColorAsState(
        when (status) {
            KeyStatus.IDLE    -> Color.Transparent
            KeyStatus.LOADING -> Color(0x1A60A5FA)
            KeyStatus.SUCCESS -> Color(0x1F10B981)
            KeyStatus.ERROR   -> Color(0x1FEF4444)
        }, tween(250), label = "glow"
    )

    // ── Icon animations ───────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse_scale"
    )
    val successScale by animateFloatAsState(
        targetValue = if (status == KeyStatus.SUCCESS) 1f else 0.5f,
        animationSpec = if (status == KeyStatus.SUCCESS) keyframes {
            durationMillis = 300
            0.5f at 0
            1.15f at 150 with LinearEasing
            1.0f at 300
        } else snap(), label = "success_scale"
    )
    val iconScale = when (status) {
        KeyStatus.LOADING -> pulseScale
        KeyStatus.SUCCESS -> successScale
        else -> 1f
    }
    val iconEmoji = when (status) {
        KeyStatus.SUCCESS -> "✅"
        KeyStatus.ERROR   -> "🔐"
        else              -> "🔑"
    }

    // ── Button ────────────────────────────────────────────────────────────────
    val buttonInteraction = remember { MutableInteractionSource() }
    val isPressed by buttonInteraction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(150), label = "btn_scale")
    val buttonGradient = if (status == KeyStatus.SUCCESS)
        Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
    else
        Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1)))
    val buttonGlowColor = if (status == KeyStatus.SUCCESS) Color(0x6610B981) else Color(0x943B82F6)
    val isDisabled = status == KeyStatus.LOADING || status == KeyStatus.SUCCESS

    fun validate() {
        if (apiKey.isBlank()) { status = KeyStatus.ERROR; return }
        status = KeyStatus.LOADING
        scope.launch {
            delay(1600)
            status = if (apiKey.startsWith("sk-") || apiKey.length >= 20) KeyStatus.SUCCESS else KeyStatus.ERROR
            if (status == KeyStatus.SUCCESS) { delay(700); onSuccess(apiKey) }
        }
    }

    AppBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        alpha = entryAlpha
                        translationY = entryOffset * density
                    }
                    .glowShadow(Color(0x1A3B82F6), 20.dp, 24.dp)
                    .background(Color(0x12FFFFFF), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                    .padding(32.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

                    // ── 1. Icon block ─────────────────────────────────────────
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                .background(
                                    Brush.linearGradient(listOf(Color(0x3860A5FA), Color(0x2E8B5CF6))),
                                    RoundedCornerShape(18.dp)
                                )
                                .border(1.dp, Color(0x4D60A5FA), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(iconEmoji, fontSize = 26.sp)
                        }
                        Text("OPENROUTER",
                            fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = Color(0xFF60A5FA))
                        Text("Enter your API Key",
                            fontFamily = DmSansFamily, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = Color(0xFFF1F5F9))
                        Text("Required to access free & paid models",
                            fontFamily = DmSansFamily, fontSize = 12.sp,
                            color = Color(0xFF4B5563), textAlign = TextAlign.Center)
                    }

                    // ── 2. Input field ────────────────────────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glowShadow(glowColor, 8.dp, 12.dp)
                                .background(Color(0x0AFFFFFF), RoundedCornerShape(12.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    if (status == KeyStatus.ERROR) status = KeyStatus.IDLE
                                },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(
                                    fontFamily = SpaceMonoFamily,
                                    fontSize = 13.sp,
                                    color = Color(0xFFE5E7EB),
                                    letterSpacing = if (!passwordVisible && apiKey.isNotEmpty()) 3.sp else 0.sp
                                ),
                                visualTransformation = if (passwordVisible)
                                    VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions.Default,
                                singleLine = true,
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (apiKey.isEmpty()) {
                                            Text("sk-or-v1-…", fontFamily = SpaceMonoFamily,
                                                fontSize = 13.sp, color = Color(0x55FFFFFF))
                                        }
                                        inner()
                                    }
                                }
                            )
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Text(if (passwordVisible) "👁" else "🙈", fontSize = 15.sp)
                            }
                        }
                        AnimatedVisibility(
                            visible = status == KeyStatus.ERROR,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(200))
                        ) {
                            Text("Invalid key — must be 20+ characters or start with sk-",
                                fontFamily = DmSansFamily, fontSize = 11.sp, color = Color(0xFFEF4444))
                        }
                    }

                    // ── 3. CTA button ─────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .graphicsLayer { scaleX = buttonScale; scaleY = buttonScale }
                            .glowShadow(buttonGlowColor, 12.dp, 13.dp)
                            .background(buttonGradient, RoundedCornerShape(13.dp))
                            .clickable(
                                interactionSource = buttonInteraction,
                                indication = null,
                                enabled = !isDisabled
                            ) { validate() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (status == KeyStatus.LOADING) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            }
                            Text(
                                text = when (status) {
                                    KeyStatus.IDLE, KeyStatus.ERROR -> "Continue →"
                                    KeyStatus.LOADING               -> "Validating…"
                                    KeyStatus.SUCCESS               -> "✓  Validated!"
                                },
                                fontFamily = DmSansFamily, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, color = Color.White
                            )
                        }
                    }

                    // ── 4. Footer ─────────────────────────────────────────────
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF9CA3AF), fontSize = 11.sp,
                                fontFamily = DmSansFamily)) {
                                append("Don't have a key? ")
                            }
                            withStyle(SpanStyle(color = Color(0xFF60A5FA), fontSize = 11.sp,
                                fontFamily = DmSansFamily)) {
                                append("Get one free at openrouter.ai →")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
