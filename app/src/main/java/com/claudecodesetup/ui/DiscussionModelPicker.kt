package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.discussion.Speaker

data class SpeakerCandidate(val provider: Provider, val model: AiModel)

/**
 * Multi-select model picker for Discussion. Lists every model from every
 * configured provider (i.e. provider has an API key set). Cap.CODING-bias
 * happens upstream via the `biasCoding` flag — we just visually flag those
 * models with a small "code" chip so the user can prefer them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionModelPickerSheet(
    prefs: AppPreferences,
    initiallySelected: List<String>,   // list of "<providerId>:<modelId>"
    biasCoding: Boolean,
    minPick: Int = 2,
    maxPick: Int = 4,
    onConfirm: (List<Speaker>) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember {
        val out = mutableListOf<SpeakerCandidate>()
        for (p in Providers.ALL) {
            val key = prefs.getApiKeyForProvider(p.id)
            if (key.isEmpty()) continue
            for (m in p.models) out.add(SpeakerCandidate(p, m))
        }
        out
    }
    val selected = remember {
        mutableStateListOf<String>().apply { addAll(initiallySelected) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexusSurface,
        contentColor = Color.White,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Pick $minPick–$maxPick speakers",
                fontFamily = DmSansFamily, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, color = Color.White,
            )
            Text(
                if (biasCoding) "Code Review mode — models with a code chip are recommended."
                else "Tap to toggle. Order is preserved for Debate / Critique modes.",
                fontFamily = DmSansFamily, fontSize = 12.sp, color = NexusText3,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (candidates.isEmpty()) {
                Text(
                    "No providers with API keys configured. Go to Login → pick a provider first.",
                    fontFamily = DmSansFamily, fontSize = 13.sp,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 480.dp),
                ) {
                    items(candidates) { cand ->
                        val id = "${cand.provider.id}:${cand.model.modelId}"
                        val isSelected = selected.contains(id)
                        val codingCap = Cap.CODING in cand.model.caps
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) NexusAccent.copy(alpha = 0.18f) else NexusSurface2,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) NexusAccent else NexusBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    if (isSelected) selected.remove(id)
                                    else if (selected.size < maxPick) selected.add(id)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isSelected) (selected.indexOf(id) + 1).toString() else "○",
                                fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NexusAccent else NexusText3,
                                modifier = Modifier.padding(end = 10.dp).width(16.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    cand.model.name, fontFamily = DmSansFamily,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                Text(
                                    cand.provider.name, fontFamily = SpaceMonoFamily,
                                    fontSize = 10.sp, color = NexusText3,
                                )
                            }
                            if (codingCap) {
                                Text(
                                    "code", fontFamily = SpaceMonoFamily,
                                    fontSize = 9.sp,
                                    color = if (biasCoding) NexusAccent else NexusText3,
                                    modifier = Modifier
                                        .background(
                                            (if (biasCoding) NexusAccent else NexusText3).copy(alpha = 0.15f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                            if (Cap.FREE in cand.model.caps) {
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    "free", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                                    color = NexusGreen,
                                    modifier = Modifier
                                        .background(NexusGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel", color = NexusText2) }
                Button(
                    onClick = {
                        val chosen = selected.mapNotNull { id ->
                            val cand = candidates.firstOrNull {
                                "${it.provider.id}:${it.model.modelId}" == id
                            } ?: return@mapNotNull null
                            val apiKey = prefs.getApiKeyForProvider(cand.provider.id)
                            val custom = prefs.getCustomBaseUrlForProvider(cand.provider.id)
                            val baseUrl = if (custom.isNotEmpty()) custom else cand.provider.baseUrl
                            Speaker(cand.provider, cand.model, apiKey, baseUrl)
                        }
                        if (chosen.size in minPick..maxPick) onConfirm(chosen)
                    },
                    enabled = selected.size in minPick..maxPick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexusAccent, contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Use ${selected.size}", fontFamily = DmSansFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
