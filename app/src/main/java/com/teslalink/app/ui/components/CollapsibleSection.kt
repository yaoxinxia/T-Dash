package com.teslalink.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.ui.theme.TL

/**
 * 可折叠分区：标题行下方一行实时状态摘要，操作内容收在里面。
 * 控制页与设置页共用——页面项目多时，收起状态本身就是一份状态总览。
 */
@Composable
fun CollapsibleSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TL.raised)
                .border(1.dp, TL.hairline, RoundedCornerShape(10.dp))
                .clickable {
                    val opening = !expanded
                    expanded = opening
                    // 展开就是"我现在关心这块状态"的时刻，交给调用方决定是否补一次读取。
                    if (opening) onExpand?.invoke()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TL.textHi)
                Text(summary, style = MaterialTheme.typography.labelMedium, color = TL.textMid)
            }
            Text(if (expanded) "▾" else "▸", color = TL.textMid, fontSize = 16.sp)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}
