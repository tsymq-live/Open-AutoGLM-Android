package com.example.open_autoglm_android.domain

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import com.ai.assistance.showerclient.ShowerController
import com.ai.assistance.showerclient.ShowerEnvironment
import com.ai.assistance.showerclient.ShellIdentity
import com.example.open_autoglm_android.service.FloatingWindowService
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.delay
import java.io.StringReader

class VirtualDisplayActionExecutor(private val context: Context) {

    suspend fun execute(actionJson: String, screenWidth: Int, screenHeight: Int): ExecuteResult {
        return try {
            Log.d("VirtualDisplayActionExecutor", "开始解析动作: ${actionJson.take(500)}")

            val jsonString = extractJsonFromText(actionJson)
            Log.d("VirtualDisplayActionExecutor", "提取的 JSON: ${jsonString.take(200)}")

            if (jsonString.isEmpty() || jsonString == actionJson.trim()) {
                val fixedJson = tryFixMalformedJson(actionJson)
                if (fixedJson.isNotEmpty()) {
                    try {
                        val jsonElement = JsonParser.parseString(fixedJson)
                        if (jsonElement.isJsonObject) {
                            val actionObj = jsonElement.asJsonObject
                            Log.d("VirtualDisplayActionExecutor", "修复后解析成功，对象: $actionObj")
                            return processActionObject(actionObj, screenWidth, screenHeight)
                        }
                    } catch (e: Exception) {
                        Log.w("VirtualDisplayActionExecutor", "修复后的 JSON 仍然无法解析", e)
                    }
                }

                return ExecuteResult(
                    success = false,
                    message = "无法从响应中提取有效的 JSON 动作。响应内容: ${actionJson.take(200)}"
                )
            }

            val jsonElement = try {
                JsonParser.parseString(jsonString)
            } catch (e: Exception) {
                Log.w("VirtualDisplayActionExecutor", "标准解析失败，尝试 lenient 模式", e)
                try {
                    val reader = JsonReader(StringReader(jsonString))
                    reader.isLenient = true
                    JsonParser.parseReader(reader)
                } catch (e2: Exception) {
                    Log.e("VirtualDisplayActionExecutor", "Lenient 模式也失败", e2)
                    val fixedJson = tryFixMalformedJson(jsonString)
                    if (fixedJson.isNotEmpty()) {
                        try {
                            return processActionObject(
                                JsonParser.parseString(fixedJson).asJsonObject,
                                screenWidth,
                                screenHeight
                            )
                        } catch (e3: Exception) {
                            Log.e("VirtualDisplayActionExecutor", "修复后仍然无法解析", e3)
                        }
                    }
                    throw e2
                }
            }

            if (!jsonElement.isJsonObject) {
                val errorMsg = if (jsonElement.isJsonPrimitive) {
                    "响应不是 JSON 对象，而是: ${jsonElement.asString.take(100)}"
                } else {
                    "响应不是 JSON 对象"
                }
                throw IllegalStateException(errorMsg)
            }

            val actionObj = jsonElement.asJsonObject
            Log.d("VirtualDisplayActionExecutor", "解析成功，对象: $actionObj")

            processActionObject(actionObj, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e("VirtualDisplayActionExecutor", "解析动作失败", e)
            ExecuteResult(success = false, message = "解析动作失败: ${e.message}")
        }
    }

    private suspend fun processActionObject(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val metadata = actionObj.get("_metadata")?.asString ?: ""

        return when (metadata) {
            "finish" -> {
                val message = actionObj.get("message")?.asString ?: "任务完成"
                ExecuteResult(success = true, message = message)
            }

            "do" -> {
                val action = actionObj.get("action")?.asString ?: ""
                if (action.isBlank()) return ExecuteResult(success = false, message = "缺少 action 参数")
                executeAction(action, actionObj, screenWidth, screenHeight)
            }

            else -> {
                val action = actionObj.get("action")?.asString ?: ""
                if (action.isNotBlank()) executeAction(action, actionObj, screenWidth, screenHeight)
                else ExecuteResult(success = false, message = "未知的动作类型: $metadata")
            }
        }
    }

    private fun convertRelativeToAbsolute(element: List<Float>, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val x = (element[0] / 1000f) * screenWidth
        val y = (element[1] / 1000f) * screenHeight
        return Pair(x, y)
    }

    private suspend fun executeAction(action: String, actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        FloatingWindowService.getInstance()?.setVisibility(false)
        delay(100)
        val result = try {
            when (action.lowercase()) {
                "launch" -> launchApp(actionObj)
                "tap" -> tap(actionObj, screenWidth, screenHeight)
                "type" -> type(actionObj)
                "swipe" -> swipe(actionObj, screenWidth, screenHeight)
                "back" -> back()
                "home" -> home()
                "longpress", "long press" -> longPress(actionObj, screenWidth, screenHeight)
                "doubletap", "double tap" -> doubleTap(actionObj, screenWidth, screenHeight)
                "wait" -> wait(actionObj)
                else -> ExecuteResult(success = false, message = "不支持的操作: $action")
            }
        } finally {
            FloatingWindowService.getInstance()?.setVisibility(true)
        }
        return result
    }

    private suspend fun launchApp(actionObj: JsonObject): ExecuteResult {
        val appName = actionObj.get("app")?.asString ?: return ExecuteResult(success = false, message = "Launch 操作缺少 app 参数")
        val packageName = getPackageName(appName)
        if (packageName == appName && !isPackageInstalled(packageName)) {
            return ExecuteResult(success = false, message = "找不到应用: $appName，且未安装此包名")
        }
        val ok = ShowerController.launchApp(packageName)
        delay(2000)
        return if (ok) ExecuteResult(success = true, actionDetail = ActionDetail("launch", text = appName))
        else ExecuteResult(success = false, message = "启动应用失败: $packageName")
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun tap(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val element = actionObj.get("element")
        if (element?.isJsonArray == true) {
            val array = element.asJsonArray
            if (array.size() >= 2) {
                val (absoluteX, absoluteY) =
                    convertRelativeToAbsolute(listOf(array[0].asFloat, array[1].asFloat), screenWidth, screenHeight)
                val ok = ShowerController.tap(absoluteX.toInt(), absoluteY.toInt())
                delay(500)
                return if (ok) {
                    ExecuteResult(
                        success = true,
                        message = "已点击坐标: ($absoluteX, $absoluteY)",
                        actionDetail = ActionDetail("tap", x1 = absoluteX, y1 = absoluteY)
                    )
                } else {
                    ExecuteResult(success = false, message = "Tap 失败: ($absoluteX, $absoluteY)")
                }
            }
            return ExecuteResult(success = false, message = "坐标格式错误")
        }

        // VirtualDisplay 无法通过无障碍树查找节点，暂不支持 text/selector 模式
        return ExecuteResult(success = false, message = "Tap 操作缺少 element 参数（VirtualDisplay 不支持按文本点击）")
    }

    private suspend fun type(actionObj: JsonObject): ExecuteResult {
        val text = actionObj.get("text")?.asString ?: return ExecuteResult(success = false, message = "Type 操作缺少 text 参数")

        val displayId = ShowerController.getDisplayId()
        val escaped = escapeForAndroidInputText(text)
        val runner = ShowerEnvironment.shellRunner
            ?: return ExecuteResult(success = false, message = "ShellRunner 未注入，无法在虚拟屏输入")

        val cmdWithDisplay = displayId?.let { "input --display $it text $escaped" }
        val first = cmdWithDisplay?.let { runner.run(it, ShellIdentity.SHELL) }
        val ok = first?.success ?: runner.run("input text $escaped", ShellIdentity.SHELL).success
        delay(500)
        return ExecuteResult(success = ok, actionDetail = ActionDetail("type", text = text))
    }

    private fun escapeForAndroidInputText(text: String): String {
        // android `input text` uses %s for spaces; keep it simple and avoid shell quoting issues.
        return text
            .replace("%", "%25")
            .replace(" ", "%s")
            .replace("\n", "%n")
    }

    private suspend fun swipe(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val start = actionObj.get("start")?.asJsonArray
        val end = actionObj.get("end")?.asJsonArray
        if (start == null || end == null || start.size() < 2 || end.size() < 2) {
            return ExecuteResult(success = false, message = "Swipe 操作缺少 start 或 end 参数")
        }
        val (startX, startY) = convertRelativeToAbsolute(listOf(start[0].asFloat, start[1].asFloat), screenWidth, screenHeight)
        val (endX, endY) = convertRelativeToAbsolute(listOf(end[0].asFloat, end[1].asFloat), screenWidth, screenHeight)
        val ok = ShowerController.swipe(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt())
        delay(500)
        return if (ok) {
            ExecuteResult(
                success = true,
                message = "已滑动从 ($startX, $startY) 到 ($endX, $endY)",
                actionDetail = ActionDetail("swipe", x1 = startX, y1 = startY, x2 = endX, y2 = endY)
            )
        } else {
            ExecuteResult(success = false, message = "Swipe 失败")
        }
    }

    private suspend fun back(): ExecuteResult {
        ShowerController.key(KeyEvent.KEYCODE_BACK)
        delay(500)
        return ExecuteResult(success = true, actionDetail = ActionDetail("back"))
    }

    private suspend fun home(): ExecuteResult {
        ShowerController.key(KeyEvent.KEYCODE_HOME)
        delay(500)
        return ExecuteResult(success = true, actionDetail = ActionDetail("home"))
    }

    private suspend fun longPress(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val element = actionObj.get("element")?.asJsonArray
            ?: return ExecuteResult(success = false, message = "LongPress 操作缺少 element 参数")
        if (element.size() < 2) return ExecuteResult(success = false, message = "LongPress 操作缺少 element 参数")
        val (x, y) = convertRelativeToAbsolute(listOf(element[0].asFloat, element[1].asFloat), screenWidth, screenHeight)
        val okDown = ShowerController.touchDown(x.toInt(), y.toInt())
        delay(600)
        val okUp = ShowerController.touchUp(x.toInt(), y.toInt())
        delay(300)
        val ok = okDown && okUp
        return ExecuteResult(
            success = ok,
            message = if (ok) "已长按坐标: ($x, $y)" else "长按失败",
            actionDetail = ActionDetail("longpress", x1 = x, y1 = y)
        )
    }

    private suspend fun doubleTap(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val element = actionObj.get("element")?.asJsonArray
            ?: return ExecuteResult(success = false, message = "DoubleTap 操作缺少 element 参数")
        if (element.size() < 2) return ExecuteResult(success = false, message = "DoubleTap 操作缺少 element 参数")
        val (x, y) = convertRelativeToAbsolute(listOf(element[0].asFloat, element[1].asFloat), screenWidth, screenHeight)
        ShowerController.tap(x.toInt(), y.toInt())
        delay(120)
        ShowerController.tap(x.toInt(), y.toInt())
        delay(500)
        return ExecuteResult(
            success = true,
            message = "已双击坐标: ($x, $y)",
            actionDetail = ActionDetail("doubletap", x1 = x, y1 = y)
        )
    }

    private suspend fun wait(actionObj: JsonObject): ExecuteResult {
        val durationMs = parseDurationMillis(actionObj.get("duration"))
        delay(durationMs)
        return ExecuteResult(success = true, message = "已等待 ${durationMs}ms", actionDetail = ActionDetail("wait"))
    }

    private fun parseDurationMillis(durationElement: JsonElement?): Long {
        if (durationElement == null) return 1000L
        if (durationElement.isJsonPrimitive) {
            val prim = durationElement.asJsonPrimitive
            if (prim.isNumber) return prim.asLong.coerceAtLeast(0L)
            if (prim.isString) {
                val raw = prim.asString.trim()
                val regex = Regex("""(?i)(\d+(?:\.\d+)?)\s*(ms|millisecond|milliseconds|s|sec|secs|second|seconds)?""")
                val match = regex.find(raw)
                if (match != null) {
                    val value = match.groupValues[1].toDoubleOrNull() ?: 1.0
                    val unit = match.groupValues.getOrNull(2)?.lowercase()
                    val millis = when (unit) {
                        "ms", "millisecond", "milliseconds" -> value
                        "s", "sec", "secs", "second", "seconds" -> value * 1000
                        else -> value * 1000
                    }
                    return millis.toLong().coerceAtLeast(0L)
                }
            }
        }
        return 1000L
    }

    private fun getPackageName(appName: String): String {
        val appPackageMap = mapOf(
            "支付宝" to "com.eg.android.AlipayGphone",
            "微信" to "com.tencent.mm",
            "WeChat" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "qq" to "com.tencent.mobileqq",
            "微博" to "com.sina.weibo",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "小红书" to "com.xingin.xhs",
            "知乎" to "com.zhihu.android",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "美团" to "com.sankuai.meituan",
            "bilibili" to "tv.danmaku.bili",
            "抖音" to "com.ss.android.ugc.aweme",
            "网易云音乐" to "com.netease.cloudmusic",
            "Settings" to "com.android.settings",
            "Chrome" to "com.android.chrome",
            "YouTube" to "com.google.android.youtube"
        )
        val mappedPackage = appPackageMap[appName]
        if (mappedPackage != null) return mappedPackage
        try {
            val pm = context.packageManager
            val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString()
                if (label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)) {
                    return info.activityInfo.packageName
                }
            }
        } catch (_: Exception) {
        }
        return appName
    }

    private fun extractJsonFromText(text: String): String {
        val trimmedText = text.trim()
        if (trimmedText.startsWith("{") && trimmedText.endsWith("}")) return trimmedText
        val jsonRegex = Regex("""\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""")
        val match = jsonRegex.find(trimmedText)
        if (match != null) return match.value
        return ""
    }

    private fun tryFixMalformedJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && !trimmed.endsWith("}")) return "$trimmed}"
        if (!trimmed.startsWith("{") && trimmed.endsWith("}")) return "{$trimmed"

        // Allow common python-like dicts
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val replaced = trimmed
                .replace("'", "\"")
                .replace(Regex("""\bTrue\b"""), "true")
                .replace(Regex("""\bFalse\b"""), "false")
                .replace(Regex("""\bNone\b"""), "null")
            return replaced
        }

        return ""
    }
}

