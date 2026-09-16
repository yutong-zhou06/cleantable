package com.obsession.schedule.ui.schedule

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsession.schedule.importer.ParsedCourse
import com.obsession.schedule.ui.theme.appDarkTheme
import com.obsession.schedule.ui.theme.courseColors

/**
 * 导入预览与确认。
 *
 * 这是刻意加的一步：教务页面的解析不可能百分之百准确，
 * 「先让用户核对、再写进课表」比「导入完再让人去发现错课」成本低得多。
 * 预览里把来源、编码、学期、逐条课程都摊开，任何异常都看得见。
 */
@Composable
fun ImportPreviewDialog(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onConfirm: (keepExisting: Boolean) -> Unit
) {
    val dark = appDarkTheme()
    var keepExisting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("导入预览", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    preview.sourceLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        text = {
            Column {
                MetaRow("共 ${preview.courses.size} 条记录", "${preview.distinctCourseCount} 门课")
                preview.termName?.let { MetaRow("学期", it) }
                MetaRow("文件编码", preview.charsetName)
                MetaRow("最晚周次", "第 ${preview.maxWeek} 周")

                if (preview.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    NotePanel(
                        lines = preview.warnings,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                if (preview.skipped.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    NotePanel(
                        lines = preview.skipped,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp)
                ) {
                    items(preview.courses) { course ->
                        CoursePreviewRow(
                            course = course,
                            argb = preview.colorsByName[course.name],
                            dark = dark
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { keepExisting = !keepExisting }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = keepExisting, onCheckedChange = { keepExisting = it })
                    Column {
                        Text("保留现有课表", fontSize = 13.sp)
                        Text(
                            if (keepExisting) {
                                "新课程会追加在现有课表之后"
                            } else {
                                "现有课程会被清空，只保留这次导入的内容"
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(keepExisting) }) {
                Text(if (keepExisting) "追加导入" else "替换导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            value,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NotePanel(
    lines: List<String>,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (line in lines) {
            Text(line, fontSize = 10.5.sp, lineHeight = 14.sp, color = content)
        }
    }
}

@Composable
private fun CoursePreviewRow(course: ParsedCourse, argb: Int?, dark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dot = argb?.let { courseColors(it, dark).accent }
            ?: MaterialTheme.colorScheme.outline
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                course.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(course.previewLine())
                    if (course.room.isNotBlank()) append(" · ${course.room}")
                    if (course.teacher.isNotBlank()) append(" · ${course.teacher}")
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
