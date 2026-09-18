package com.obsession.schedule.ui.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 只收数字的小输入框（节次、连堂、周次都用它）。
 *
 * 关键点：**编辑过程中允许空**。
 *
 * 之前这里把值直接当 Int 用（`value.toString()` 显示、`toIntOrNull() ?: 0` 回写），
 * 于是「删掉默认的 1」会立刻被填回 1 —— 用户想输入 2，只能追加，结果变成 12，
 * 根本没法改成一个一位数。现在文本单独存一份，中途可以是空的，只在**失焦或按完成**
 * 时才钳到合法范围；真留空则回落到进来时的值，所以保存出来的数据始终有效。
 */
@Composable
fun NumberField(
    value: Int,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    labelSize: TextUnit = 11.sp,
    onChange: (Int) -> Unit
) {
    // 跟随外部值变化重建（例如保存后再次打开），编辑期间外部值不变、不会被重置
    var text by remember(value) { mutableStateOf(value.toString()) }
    var focusedOnce by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val typed = text.filter { it.isDigit() }.take(2).toIntOrNull()
        val settled = (typed ?: value).coerceIn(range)
        text = settled.toString()
        if (settled != value) onChange(settled)
    }

    val parsed = text.toIntOrNull() ?: -1

    OutlinedTextField(
        value = text,
        // 只留数字、最多两位；不在这里做范围校验，否则又变成「打不进一位数」
        onValueChange = { raw -> text = raw.filter { it.isDigit() }.take(2) },
        label = { Text(label, fontSize = labelSize) },
        singleLine = true,
        isError = parsed !in range,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focusManager.clearFocus()
        }),
        modifier = modifier.onFocusChanged { state ->
            if (state.isFocused) {
                focusedOnce = true
            } else if (focusedOnce) {
                commit()
            }
        }
    )
}
