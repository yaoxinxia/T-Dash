package com.teslalink.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.ui.theme.TL

/** 语义色调：所有状态反馈统一走这 6 档，避免各页自己写十六进制色。 */
enum class Tone { Neutral, Good, Warn, Bad, Info, Data }

private fun Tone.color(): Color = when (this) {
    Tone.Neutral -> TL.textMid
    Tone.Good -> TL.good
    Tone.Warn -> TL.warn
    Tone.Bad -> TL.bad
    Tone.Info -> TL.accent
    Tone.Data -> TL.data
}

/** 区块小标题：细字距大写字母感，压在卡片上方建立层级。 */
@Composable
fun SectionLabel(
    text: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            color = TL.textLow,
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TL.textLow)
        }
    }
}

/** 一级容器：靠更亮的表面 + 1dp 描边表达层次，不用阴影。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    tight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TL.rCard)
            .background(TL.surface)
            .border(1.dp, TL.hairline, TL.rCard)
            .padding(if (tight) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 10.dp),
    ) {
        if (title != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    title?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = TL.textHi)
                    }
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = TL.textLow,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                trailing?.invoke(this)
            }
        }
        content()
    }
}

/** 状态胶囊：紧凑地表达“现在是什么状态 / 能不能用”。 */
@Composable
fun Pill(
    text: String,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val fg = if (selected) TL.accent else tone.color()
    val bg = if (selected) TL.accentSoft else fg.copy(alpha = 0.10f)
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, fg.copy(alpha = if (selected) 0.55f else 0.28f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected || onClick != null) fg else TL.textMid,
            maxLines = 1,
        )
    }
}

/**
 * 动作块：取代普通按钮。主标题 = 动作，副标题 = 车辆当前状态/被禁用的原因，
 * 右侧圆点 = 该动作对应状态的语义色。按压有 0.985 缩放反馈。
 */
@Composable
fun ActionTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateLine: String? = null,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
    emphasize: Boolean = false,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.985f else 1f, tween(120), label = "tile")
    val accent = tone.color()
    Row(
        modifier = modifier
            .scale(scale)
            .clip(TL.rTile)
            .background(if (emphasize && enabled) TL.accentSoft else TL.raised)
            .border(
                width = 1.dp,
                color = when {
                    !enabled -> TL.hairlineSoft
                    emphasize -> TL.accent.copy(alpha = 0.5f)
                    tone != Tone.Neutral -> accent.copy(alpha = 0.35f)
                    else -> TL.hairline
                },
                shape = TL.rTile,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 58.dp)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) TL.textHi else TL.textLow,
            )
            stateLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) accent.copy(alpha = 0.9f) else TL.textLow.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                )
            }
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = TL.textLow,
            )
        }
        if (enabled) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (tone == Tone.Neutral) TL.textLow else accent)
            )
        }
    }
}

/** 数值块：状态页/统计页读数。数字等宽，跳动不晃版。 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    footnote: String? = null,
) {
    Column(
        modifier = modifier
            .clip(TL.rTile)
            .background(TL.raised)
            .border(1.dp, TL.hairlineSoft, TL.rTile)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label.uppercase(),
            style = TL.eyebrow,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = if (tone == Tone.Neutral) TL.textHi else tone.color(),
            )
            unit?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = TL.textLow,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        footnote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TL.textLow, maxLines = 2)
        }
    }
}

/** 提示条：解释“为什么不可用 / 下一步做什么”，按语义色着色。 */
@Composable
fun HintBar(text: String, tone: Tone = Tone.Info, modifier: Modifier = Modifier) {
    val accent = tone.color()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TL.rTile)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.28f), TL.rTile)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = TL.textHi.copy(alpha = 0.88f),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 页头：标题 + 一句话说明 + 右侧动作区。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp,
                color = TL.textHi,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TL.textLow,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** 键值行：卡片内的紧凑读数。 */
@Composable
fun KeyValue(key: String, value: String, tone: Tone = Tone.Neutral) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = TL.textLow)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = if (tone == Tone.Neutral) TL.textHi else tone.color(),
        )
    }
}
