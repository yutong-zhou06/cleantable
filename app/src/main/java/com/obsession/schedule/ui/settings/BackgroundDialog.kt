package com.obsession.schedule.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.obsession.schedule.data.BgConfig

/**
 * 课表背景设置。
 *
 * 蒙层语义：数值是不透明度，浅色主题盖白、深色主题盖黑。
 * 调得越淡图片越清楚 —— 但低于 0.4 时课表底下会再垫一层淡色基底，
 * 保证最淡的状态下课程文字依然读得清（「对比度不足自动加深」）。
 */
@Composable
fun BackgroundDialog(
    current: BgConfig,
    dark: Boolean,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onMaskChange: (Float) -> Unit,
    onClear: () -> Unit
) {
    var mask by remember { mutableStateOf(current.mask) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("课表背景", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "图片只铺在课表网格区域；长按网格随时可以回到这里。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(18.dp))

                // 效果预览：主题底色 + 模拟图片色块 + 蒙层
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    Row(modifier = Modifier.fillMaxWidth().height(92.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(92.dp)
                                .background(Color(0xFF7A9BD4))
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(92.dp)
                                .background(Color(0xFFD9A06B))
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(92.dp)
                                .background(Color(0xFF6FAF8C))
                        )
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                (if (dark) Color.Black else Color.White)
                                    .copy(alpha = mask)
                            )
                    )
                    Text(
                        "预览",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mask > 0.55f) {
                            if (dark) Color.White else Color.Black
                        } else {
                            if (dark) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.85f)
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                if (dark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.35f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "蒙层浓度 ${(mask * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = mask,
                    onValueChange = { mask = it.coerceIn(BgConfig.MIN_MASK, BgConfig.MAX_MASK) },
                    onValueChangeFinished = { onMaskChange(mask) },
                    valueRange = BgConfig.MIN_MASK..BgConfig.MAX_MASK,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (mask < 0.4f) "当前较透明，已自动加深一层保证文字可读"
                    else "浓度越高文字越清楚",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (current.hasImage) {
                        TextButton(
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        ) { Text("移除图片") }
                        Button(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                            Text("换一张")
                        }
                    } else {
                        Button(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                            Text("选择图片")
                        }
                    }
                }
            }
        }
    }
}
