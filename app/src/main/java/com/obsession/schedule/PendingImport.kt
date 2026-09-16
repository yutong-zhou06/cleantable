package com.obsession.schedule

/**
 * 内置浏览器导入的中转站。
 *
 * BrowserImportActivity 抓到页面 HTML 后，先把它放进这里再跳回主页；
 * 主页读走后置空。为什么不塞进 Intent —— 教务课表页的 DOM 动辄一两 MB，
 * 超 Intent 的 1MB 事务上限会直接 TransactionTooLargeException。
 */
object PendingImport {
    @Volatile
    var html: String? = null

    fun consume(): String? {
        val value = html
        html = null
        return value
    }
}
