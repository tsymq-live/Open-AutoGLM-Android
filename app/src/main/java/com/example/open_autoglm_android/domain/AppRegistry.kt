package com.example.open_autoglm_android.domain

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * 负责应用名称到包名的映射管理
 */
object AppRegistry {
    private const val TAG = "AppRegistry"
    private const val CONFIG_FILE_NAME = "app_mapping.json"
    private const val CONFIG_DIR_NAME = "OpenAutoGLM"
    
    // 内置常用 App 映射保底
    private val defaultMap = mapOf(
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "抖音" to "com.ss.android.ugc.aweme",
        "小红书" to "com.xingin.xhs",
        "设置" to "com.android.settings",
        "浏览器" to "com.android.chrome"
    )

    @Volatile
    private var appPackageMap: Map<String, String> = defaultMap

    fun initialize(context: Context) {
        if (loadFromInternalDir(context)) return
        loadFromAssets(context)
    }

    private fun loadFromInternalDir(context: Context): Boolean {
        return try {
            val configDir = File(context.filesDir, CONFIG_DIR_NAME)
            loadConfigFile(context, configDir, "Internal Storage")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from internal storage", e)
            false
        }
    }

    private fun loadConfigFile(context: Context, dir: File, sourceName: String): Boolean {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val configFile = File(dir, CONFIG_FILE_NAME)
            if (!configFile.exists()) {
                copyAssetsToLocalStorage(context, CONFIG_FILE_NAME, configFile)
            }
            if (configFile.exists() && configFile.canRead()) {
                val jsonString = configFile.readText()
                val type = object : TypeToken<Map<String, String>>() {}.type
                val map: Map<String, String>? = Gson().fromJson(jsonString, type)
                if (!map.isNullOrEmpty()) {
                    // 合并内置映射和用户映射
                    appPackageMap = defaultMap + map
                    Log.i(TAG, "Loaded ${appPackageMap.size} mappings from $sourceName")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$sourceName] Error", e)
        }
        return false
    }

    private fun loadFromAssets(context: Context) {
        try {
            context.assets.open(CONFIG_FILE_NAME).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    val map: Map<String, String>? = Gson().fromJson(reader, type)
                    if (map != null) appPackageMap = defaultMap + map
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config from assets", e)
        }
    }

    private fun copyAssetsToLocalStorage(context: Context, assetName: String, destinationFile: File): Boolean {
        return try {
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getPackageName(appName: String): String {
        val trimmedName = appName.trim()
        // 优先匹配映射表，如果没找到且看起来已经是包名则返回原样
        return appPackageMap[trimmedName] ?: trimmedName
    }
}
