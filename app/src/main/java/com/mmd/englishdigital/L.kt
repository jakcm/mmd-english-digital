package com.mmd.englishdigital

/**
 * ★ 统一日志门面（决策 A1 / B1 / C1 / D2 / E1 / F2）
 *
 * 目的：把全部日志收敛到一个开关，默认【不输出】，避免浪费性能与资源占用。
 *
 * 开启方式（启动参数，B1）：
 *     am start -n com.mmd.englishdigital/.MainActivity --ez log true
 *
 * 默认值（F2）：
 *     debug 包默认开（BuildConfig.DEBUG）
 *     release 包默认关 —— 与"默认不打印"的要求一致
 *
 * 注：本类内部使用全限定 android.util.Log，避免与门面方法同名冲突。
 */
object L {

    /** 全局开关；由各入口 Activity 在 onCreate 时按启动参数设置 */
    @Volatile
    var enabled: Boolean = false

    /** 按启动参数初始化；返回是否开启 */
    fun init(logFlagFromIntent: Boolean?): Boolean {
        enabled = logFlagFromIntent ?: BuildConfig.DEBUG
        return enabled
    }

    fun v(tag: String, msg: String) {
        if (enabled) android.util.Log.v(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (enabled) android.util.Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) android.util.Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (enabled) android.util.Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        if (enabled) android.util.Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String) {
        if (enabled) android.util.Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        if (enabled) android.util.Log.e(tag, msg, tr)
    }
}
