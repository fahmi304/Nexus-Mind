package com.claudecodesetup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

// ── Data ─────────────────────────────────────────────────────────────────────

data class AiModel(
    val id: Int,
    val name: String,
    val maker: String,
    val category: String,
    val tokens: String,
    val speed: Int,
    val badge: String,
    val color: Color,
    val emoji: String
)

private val ALL_MODELS = listOf(
    AiModel(1,  "GPT-4o",        "OpenAI",        "Multimodal",   "128K", 72, "Pro",   Color(0xFF3B82F6), "🧠"),
    AiModel(2,  "Claude 3.5",    "Anthropic",     "Reasoning",    "200K", 65, "Top",   Color(0xFF8B5CF6), "🎭"),
    AiModel(3,  "Gemini 1.5",    "Google",        "Multimodal",   "1M",   60, "Pro",   Color(0xFF10B981), "✨"),
    AiModel(4,  "Llama 3.1",     "Meta",          "Open Source",  "128K", 45, "Free",  Color(0xFFF97316), "🦙"),
    AiModel(5,  "Mistral L",     "Mistral",       "Efficient",    "128K", 80, "Fast",  Color(0xFF06B6D4), "⚡"),
    AiModel(6,  "DeepSeek R1",   "DeepSeek",      "Reasoning",    "64K",  55, "Smart", Color(0xFF6366F1), "🔍"),
    AiModel(7,  "GPT-4o Mini",   "OpenAI",        "Compact",      "128K", 90, "Lite",  Color(0xFF0EA5E9), "🚀"),
    AiModel(8,  "Qwen 2.5",      "Alibaba",       "Open Source",  "128K", 68, "Free",  Color(0xFFF59E0B), "🌟"),
    AiModel(9,  "Gemini Flash",  "Google",        "Efficient",    "1M",   95, "Fast",  Color(0xFF34D399), "⚡"),
    AiModel(10, "Claude Haiku",  "Anthropic",     "Compact",      "200K", 92, "Lite",  Color(0xFFA78BFA), "🎭"),
    AiModel(11, "Phi-3 Med",     "Microsoft",     "Compact",      "128K", 85, "Free",  Color(0xFF818CF8), "🔮"),
    AiModel(12, "Codestral",     "Mistral",       "Coding",       "32K",  78, "Code",  Color(0xFF22D3EE), "💻"),
    AiModel(13, "DeepSeek V2",   "DeepSeek",      "Coding",       "128K", 70, "Code",  Color(0xFF60A5FA), "🔧"),
    AiModel(14, "LLaVA 1.6",     "Open",          "Vision",       "4K",   62, "Free",  Color(0xFFF472B6), "👁"),
    AiModel(15, "Pixtral 12B",   "Mistral",       "Vision",       "128K", 74, "Fast",  Color(0xFF2DD4BF), "🎨"),
    AiModel(16, "Mixtral 8x7B",  "Mistral",       "Open Source",  "32K",  76, "Free",  Color(0xFFC084FC), "🧬"),
    AiModel(17, "WizardLM 2",    "Microsoft",     "Reasoning",    "32K",  58, "Smart", Color(0xFFFB7185), "🧙"),
    AiModel(18, "Nous Hermes",   "NousResearch",  "Efficient",    "8K",   88, "Fast",  Color(0xFF86EFAC), "🌿"),
)

private val CATEGORIES = listOf("All", "Reasoning", "Multimodal", "Efficient",
    "Vision", "Compact", "Coding", "Open Source")
private const val PAGE_SIZE = 9

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ModelPickerScreen(apiKey: String, onBack: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    var page by remember { mutableStateOf(0) }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val filtered = remember(filter) {
        if (filter == "All") ALL_MODELS else ALL_MODELS.filter { it.category == filter }
    }
    LaunchedEffect(filter) { page = 0 }

    val totalPages = remember(filtered) { maxOf(1, ceil(filtered.size / PAGE_SIZE.toFloat()).toInt()) }
    val paged = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
    val selectedModel = ALL_MODELS.find { it.id == selectedId }

    // Entry animation
    var entered by remember { mutableStateOf(false) }
    val entryAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(500), label = "alpha")
    LaunchedEffect(Unit) { entered = true }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = entryAlpha }
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button + title
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        "←",
                        fontSize = 20.sp,
                        color = Color(0xFF60A5FA),
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("OPENROUTER",
                            fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = Color(0xB360A5FA))
                        Text("Choose Your Model",
                            fontFamily = DmSansFamily, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                // Right pills
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // API key pill
                    val shortKey = if (apiKey.length > 8) apiKey.take(8) + "…" else apiKey
                    Row(
                        modifier = Modifier
                            .background(Color(0x1F10B981), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0x5010B981), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(Modifier.size(6.dp).background(Color(0xFF10B981), CircleShape))
                        Text(shortKey, fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = Color(0xFF10B981))
                    }
                    // Selected model pill
                    AnimatedVisibility(
                        visible = selectedModel != null,
                        enter = fadeIn() + slideInVertically { -it },
                        exit = fadeOut() + slideOutVertically { -it }
                    ) {
                        selectedModel?.let { m ->
                            Row(
                                modifier = Modifier
                                    .background(m.color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                    .border(1.dp, m.color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(m.emoji, fontSize = 10.sp)
                                Text("Selected", fontFamily = DmSansFamily,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold, color = m.color)
                            }
                        }
                    }
                }
            }

            // ── Filter chips ──────────────────────────────────────────────────
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(CATEGORIES) { cat ->
                    val isActive = cat == filter
                    val chipBg by animateColorAsState(
                        if (isActive) Color(0x2E60A5FA) else Color(0x0DFFFFFF),
                        tween(150), label = "chip_bg"
                    )
                    val chipBorder by animateColorAsState(
                        if (isActive) Color(0x7260A5FA) else Color(0x17FFFFFF),
                        tween(150), label = "chip_border"
                    )
                    val chipText by animateColorAsState(
                        if (isActive) Color(0xFF93C5FD) else Color(0xFF6B7280),
                        tween(150), label = "chip_text"
                    )
                    Box(
                        modifier = Modifier
                            .background(chipBg, RoundedCornerShape(20.dp))
                            .border(1.dp, chipBorder, RoundedCornerShape(20.dp))
                            .clickable { filter = cat }
                            .padding(horizontal = 11.dp, vertical = 4.dp)
                    ) {
                        Text(cat, fontFamily = DmSansFamily, fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, color = chipText)
                    }
                }
            }

            // ── Model grid ────────────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp)
            ) {
                items(paged, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isSelected = selectedId == model.id,
                        onSelect = { selectedId = model.id }
                    )
                }
                // Empty slot placeholders
                val emptyCount = PAGE_SIZE - paged.size
                items(emptyCount) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(0.85f)
                            .border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(12.dp))
                            .background(Color(0x03FFFFFF), RoundedCornerShape(12.dp))
                    )
                }
            }

            // ── Pagination ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prev
                PaginationButton(
                    label = "‹", enabled = page > 0, onClick = { page-- }
                )
                // Page numbers
                repeat(totalPages) { i ->
                    val isActive = i == page
                    val pageBg by animateColorAsState(
                        if (isActive) Color(0x3860A5FA) else Color(0x0DFFFFFF),
                        tween(150), label = "page_bg"
                    )
                    val pageBorder by animateColorAsState(
                        if (isActive) Color(0x8060A5FA) else Color.Transparent,
                        tween(150), label = "page_border"
                    )
                    val pageText by animateColorAsState(
                        if (isActive) Color(0xFF93C5FD) else Color(0xFF374151),
                        tween(150), label = "page_text"
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(pageBg, RoundedCornerShape(8.dp))
                            .border(1.dp, pageBorder, RoundedCornerShape(8.dp))
                            .clickable { page = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", fontFamily = DmSansFamily, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, color = pageText)
                    }
                }
                // Next
                PaginationButton(
                    label = "›", enabled = page < totalPages - 1, onClick = { page++ }
                )
            }
        }
    }
}

@Composable
private fun ModelCard(model: AiModel, isSelected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(150), label = "card_scale")

    val cardBg by animateColorAsState(
        if (isSelected) model.color.copy(alpha = 0.22f) else Color(0x0FFFFFFF),
        tween(200), label = "card_bg"
    )
    val cardBorder by animateColorAsState(
        if (isSelected) model.color.copy(alpha = 0.66f) else Color(0x14FFFFFF),
        tween(200), label = "card_border"
    )

    Box(
        modifier = Modifier
            .aspectRatio(0.85f)
            .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
            .run {
                if (isSelected) glowShadow(model.color.copy(alpha = 0.30f), 14.dp, 12.dp) else this
            }
            .background(cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { onSelect() }
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Emoji icon box
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(model.color.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                    .border(1.dp, model.color.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(model.emoji, fontSize = 15.sp)
            }

            // Name + maker
            Column(modifier = Modifier.padding(top = 6.dp)) {
                Text(model.name, fontFamily = DmSansFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFFF1F5F9),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp)
                Text(model.maker, fontFamily = DmSansFamily, fontSize = 10.sp,
                    color = Color(0xFF374151), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Speed bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x0FFFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(model.speed / 100f)
                        .fillMaxHeight()
                        .background(
                            Brush.linearGradient(
                                listOf(model.color.copy(alpha = 0.55f), model.color)
                            )
                        )
                )
            }

            // Bottom row: badge + tokens
            Column {
                Divider(color = Color(0x0DFFFFFF), thickness = 1.dp,
                    modifier = Modifier.padding(bottom = 5.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(model.color.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                            .border(1.dp, model.color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(model.badge, fontFamily = DmSansFamily, fontSize = 7.sp,
                            fontWeight = FontWeight.Bold, color = model.color)
                    }
                    Text(model.tokens, fontFamily = SpaceMonoFamily, fontSize = 7.sp,
                        color = Color(0xFF374151))
                }
            }
        }

        // Checkmark overlay when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(14.dp)
                    .background(model.color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

@Composable
private fun PaginationButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.2f },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontFamily = DmSansFamily, fontSize = 14.sp, color = Color(0xFF6B7280))
    }
}
