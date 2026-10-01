package com.halo.yunquespark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.yunquespark.App
import java.time.LocalDate
import java.time.YearMonth

/**
 * 卡片日历：月历网格，有卡片的日期带圆点；点任意日期立即显示当日卡片。
 * 数据在打开时一次性从本地库读出（cards 表按天为主键），查看全程零等待、不现场生成。
 */
@Composable
fun CalendarScreen(onClose: () -> Unit, onOpenNote: (String) -> Unit) {
    val db = App.instance.db
    var cards by remember { mutableStateOf(db.cardAll().associateBy { it.day }) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now().toString()) }
    val today = LocalDate.now().toString()

    val firstOffset = month.atDay(1).dayOfWeek.value - 1   // 周一开头
    val daysInMonth = month.lengthOfMonth()
    val rows = (firstOffset + daysInMonth + 6) / 7

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("卡片日历", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "共 ${cards.size} 张 · 收藏 ${cards.values.count { it.favorite }} 张",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 月份导航
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 22.sp) }
            Text(
                "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 22.sp) }
        }

        // 星期表头
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (w in listOf("一", "二", "三", "四", "五", "六", "日")) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 日期网格
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - firstOffset + 1
                    Box(
                        Modifier.weight(1f).heightIn(min = 46.dp)
                            .clickable(enabled = dayNum in 1..daysInMonth) { selected = month.atDay(dayNum).toString() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayNum in 1..daysInMonth) {
                            val date = month.atDay(dayNum).toString()
                            val has = cards.containsKey(date)
                            val isSel = date == selected
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$dayNum",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                                Box(
                                    Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape)
                                        .background(if (has) (if (isSel) Color.White else MaterialTheme.colorScheme.primary) else Color.Transparent)
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // 当日卡片（纯 DB 读取，即时显示）
        val card = cards[selected]
        if (card != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Text("当日灵感 · ${card.day}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.favorite) {
                        Icon(Icons.Filled.Favorite, null, tint = Color(0xFFD95B6C), modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(card.body, style = MaterialTheme.typography.bodyMedium, fontSize = 17.sp, lineHeight = 27.sp)
                if (card.comment.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(card.comment, style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "轻触查看原文 →", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenNote(card.sourceNoteId) },
                )
                Spacer(Modifier.height(40.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "该日没有卡片", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected == today) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "今天的卡片会在打开 app 或到达推送时间时由 AI 选出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
