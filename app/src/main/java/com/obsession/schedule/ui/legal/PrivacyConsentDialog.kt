package com.obsession.schedule.ui.legal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次启动的隐私政策同意弹窗。
 *
 * 三条硬约束（国内应用商店审核必查，别改）：
 * 1. **必须在用户使用任何功能之前出现** —— 所以它挡在 MainContent 前面，而不是「设置里的一个条目」；
 * 2. **不能点外部或按返回键绕过** —— onDismissRequest 故意留空；
 * 3. **必须提供「不同意」的真实出口** —— 点了直接退出应用，不做「只能同意」的假选项。
 */
@Composable
fun PrivacyConsentDialog(
    onViewPolicy: () -> Unit,
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    AlertDialog(
        // 故意不响应：点弹窗外部、按系统返回键都不关闭，必须先做出选择
        onDismissRequest = { },
        title = {
            Text("隐私政策", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    "「执课」是一款无需注册、无需登录的本地课表工具。\n\n" +
                        "· 不收集任何个人信息：不获取姓名、手机号、位置、通讯录、设备标识\n" +
                        "· 不上传任何数据：课表与作息全部只保存在本机\n" +
                        "· 无广告、无统计、无账号、无云同步\n" +
                        "· 唯一权限「网络访问」仅用于内置浏览器打开你指定的教务网站，" +
                        "登录密码不经过本应用",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "点击「同意并继续」即表示你已阅读并同意本隐私政策。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "查看完整《隐私政策》",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onViewPolicy)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text("同意并继续", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text("不同意并退出", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
