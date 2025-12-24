package com.example.open_autoglm_android.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.example.open_autoglm_android.service.MyInputMethodService

object InputMethodHelper {
    private const val TAG = "InputMethodHelper"

    /**
     * 获取本应用输入法在系统中的正式 ID
     * 优先从系统安装列表中获取，确保 ID 格式与系统一致（解决全路径/缩写路径不匹配问题）
     */
    fun getMyInputMethodId(context: Context): String {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val myComponentName = ComponentName(context, MyInputMethodService::class.java)

        // 遍历所有已安装的输入法
        val imis = imm.inputMethodList
        val myImi = imis.find { it.component == myComponentName }

        val id = myImi?.id ?: myComponentName.flattenToString()
        Log.v(TAG, "获取输入法ID: $id")
        return id
    }

    /**
     * 检查当前应用输入法是否已在系统中启用
     */
    fun isInputMethodEnabled(context: Context): Boolean {
        val enabledMethods = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: return false
        
        val myId = getMyInputMethodId(context)
        return enabledMethods.contains(myId)
    }

    /**
     * 检查当前默认输入法是否为本应用输入法
     */
    fun isCurrentInputMethod(context: Context): Boolean {
        val currentMethod = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return false
        
        val myId = getMyInputMethodId(context)
        return currentMethod == myId
    }

    /**
     * 如果有权限且已启用，自动切换到本应用输入法
     */
    fun switchToMyInputMethod(context: Context): Boolean {
        if (!AuthHelper.hasWriteSecureSettingsPermission(context)) {
            Log.d(TAG, "没有 WRITE_SECURE_SETTINGS 权限，跳过自动切换")
            return false
        }

        val myId = getMyInputMethodId(context)
        val currentMethod = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        
        Log.d(TAG, "输入法状态检查: 当前=$currentMethod, 目标=$myId")

        try {
            // 1. 确保输入法已启用 (添加到 ENABLED_INPUT_METHODS)
            val enabledMethods = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""
            
            if (!enabledMethods.contains(myId)) {
                Log.d(TAG, "AutoGLM 输入法不在启用列表中，正在启用...")
                val newEnabledMethods = if (enabledMethods.isEmpty()) myId else "$enabledMethods:$myId"
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_INPUT_METHODS,
                    newEnabledMethods
                )
            }

            // 2. 如果已经匹配，则退出
            if (currentMethod == myId) {
                Log.d(TAG, "当前已是 AutoGLM 输入法，无需操作")
                return true
            }

            // 3. 执行切换
            Log.d(TAG, "正在写入安全设置切换到: $myId")
            
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                myId
            )
            // 重置子类型以确保生效
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                -1
            )
            
            // 验证结果
            val verify = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            Log.d(TAG, "切换验证结果: $verify")
            
            return verify == myId
        } catch (e: Exception) {
            Log.e(TAG, "切换输入法异常: ${e.message}")
            return false
        }
    }
}
