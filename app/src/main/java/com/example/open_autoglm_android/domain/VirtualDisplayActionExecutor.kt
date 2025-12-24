package com.example.open_autoglm_android.domain

import android.content.Context
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

            if (jsonString.isEmpty()) {
                return ExecuteResult(success = false, message = "无法提取有效的 JSON 动作")
            }

            val jsonElement = try {
                JsonParser.parseString(jsonString)
            } catch (e: Exception) {
                Log.w("VirtualDisplayActionExecutor", "标准解析失败，尝试 lenient 模式", e)
                val reader = JsonReader(StringReader(jsonString))
                reader.isLenient = true
                JsonParser.parseReader(reader)
            }

            if (!jsonElement.isJsonObject) throw IllegalStateException("响应不是 JSON 对象")

            val actionObj = jsonElement.asJsonObject
            processActionObject(actionObj, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e("VirtualDisplayActionExecutor", "执行失败", e)
            ExecuteResult(success = false, message = "执行失败: ${e.message}")
        }
    }

    private suspend fun processActionObject(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val metadata = actionObj.get("_metadata")?.asString ?: ""
        return when (metadata) {
            "finish" -> ExecuteResult(success = true, message = actionObj.get("message")?.asString ?: "任务完成")
            "do" -> {
                val action = actionObj.get("action")?.asString ?: ""
                executeAction(action, actionObj, screenWidth, screenHeight)
            }
            else -> {
                val action = actionObj.get("action")?.asString ?: ""
                if (action.isNotBlank()) executeAction(action, actionObj, screenWidth, screenHeight)
                else ExecuteResult(success = false, message = "未知动作类型")
            }
        }
    }

    private suspend fun executeAction(action: String, actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        FloatingWindowService.getInstance()?.setVisibility(false)
        delay(100)
        val result = try {
            when (action.lowercase()) {
                "launch" -> launchApp(actionObj)
                "tap" -> tap(actionObj, screenWidth, screenHeight)
                "type" -> type(actionObj, screenWidth, screenHeight)
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
        val appName = actionObj.get("app")?.asString ?: return ExecuteResult(success = false, message = "缺少 app 参数")
        val packageName = getPackageName(appName)
        Log.d("VirtualDisplayActionExecutor", "尝试启动应用: $appName -> $packageName")
        
        val ok = ShowerController.launchApp(packageName)
        if (ok) {
            delay(2500) // 给应用更长的启动加载时间
            return ExecuteResult(success = true, actionDetail = ActionDetail("launch", text = appName))
        } else {
            return ExecuteResult(success = false, message = "无法在虚拟屏启动应用: $packageName")
        }
    }

    private suspend fun tap(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val element = actionObj.get("element")?.asJsonArray ?: return ExecuteResult(success = false, message = "缺少坐标")
        val (absX, absY) = convertRelativeToAbsolute(listOf(element[0].asFloat, element[1].asFloat), screenWidth, screenHeight)
        val ok = ShowerController.tap(absX.toInt(), absY.toInt())
        return if (ok) ExecuteResult(success = true, actionDetail = ActionDetail("tap", x1 = absX, y1 = absY))
        else ExecuteResult(success = false, message = "点击失败")
    }

    private suspend fun type(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val text = actionObj.get("text")?.asString ?: return ExecuteResult(success = false, message = "缺少文本")
        
        // 如果包含坐标，记录 detail 以便绘制红点
        var detail: ActionDetail? = null
        val element = actionObj.get("element")?.asJsonArray
        if (element != null && element.size() >= 2) {
            val (absX, absY) = convertRelativeToAbsolute(listOf(element[0].asFloat, element[1].asFloat), screenWidth, screenHeight)
            detail = ActionDetail("type", x1 = absX, y1 = absY, text = text)
            // 先点击输入框
            ShowerController.tap(absX.toInt(), absY.toInt())
            delay(500)
        } else {
            detail = ActionDetail("type", text = text)
        }

        val displayId = ShowerController.getDisplayId()
        val runner = ShowerEnvironment.shellRunner ?: return ExecuteResult(success = false, message = "ShellRunner不可用")
        
        // 优先尝试指定 display 的输入
        val cmd = if (displayId != null) "input --display $displayId text ${escapeForAndroidInputText(text)}" 
                  else "input text ${escapeForAndroidInputText(text)}"
        val ok = runner.run(cmd, ShellIdentity.SHELL).success
        return ExecuteResult(success = ok, actionDetail = detail)
    }

    private suspend fun swipe(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val start = actionObj.get("start")?.asJsonArray ?: return ExecuteResult(success = false, message = "缺少起点")
        val end = actionObj.get("end")?.asJsonArray ?: return ExecuteResult(success = false, message = "缺少终点")
        val (sX, sY) = convertRelativeToAbsolute(listOf(start[0].asFloat, start[1].asFloat), screenWidth, screenHeight)
        val (eX, eY) = convertRelativeToAbsolute(listOf(end[0].asFloat, end[1].asFloat), screenWidth, screenHeight)
        val ok = ShowerController.swipe(sX.toInt(), sY.toInt(), eX.toInt(), eY.toInt())
        return ExecuteResult(success = ok, actionDetail = ActionDetail("swipe", x1 = sX, y1 = sY, x2 = eX, y2 = eY))
    }

    private suspend fun back(): ExecuteResult = ExecuteResult(success = ShowerController.key(KeyEvent.KEYCODE_BACK), actionDetail = ActionDetail("back"))
    private suspend fun home(): ExecuteResult = ExecuteResult(success = ShowerController.key(KeyEvent.KEYCODE_HOME), actionDetail = ActionDetail("home"))

    private suspend fun longPress(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val element = actionObj.get("element")?.asJsonArray ?: return ExecuteResult(success = false, message = "缺少坐标")
        val (x, y) = convertRelativeToAbsolute(listOf(element[0].asFloat, element[1].asFloat), screenWidth, screenHeight)
        val ok = ShowerController.touchDown(x.toInt(), y.toInt()) && delay(600).let { true } && ShowerController.touchUp(x.toInt(), y.toInt())
        return ExecuteResult(success = ok, actionDetail = ActionDetail("longpress", x1 = x, y1 = y))
    }

    private suspend fun doubleTap(actionObj: JsonObject, screenWidth: Int, screenHeight: Int): ExecuteResult {
        val element = actionObj.get("element")?.asJsonArray ?: return ExecuteResult(success = false, message = "缺少坐标")
        val (x, y) = convertRelativeToAbsolute(listOf(element[0].asFloat, element[1].asFloat), screenWidth, screenHeight)
        ShowerController.tap(x.toInt(), y.toInt())
        delay(150)
        val ok = ShowerController.tap(x.toInt(), y.toInt())
        return if (ok) ExecuteResult(success = true, actionDetail = ActionDetail("doubletap", x1 = x, y1 = y))
        else ExecuteResult(success = false, message = "双击失败")
    }

    private suspend fun wait(actionObj: JsonObject): ExecuteResult {
        delay(1000)
        return ExecuteResult(success = true, actionDetail = ActionDetail("wait"))
    }

    private fun convertRelativeToAbsolute(element: List<Float>, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        return Pair((element[0] / 1000f) * screenWidth, (element[1] / 1000f) * screenHeight)
    }

    private fun getPackageName(appName: String): String {
        return AppRegistry.getPackageName(appName)
    }

    private fun escapeForAndroidInputText(text: String): String = text.replace(" ", "%s").replace("\n", "%n")

    private fun extractJsonFromText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonParser.parseString(trimmed)
                return trimmed
            } catch (e: Exception) { }
        }
        
        // 尝试从 do(action="...", ...) 这种格式转换
        val fixedJson = tryFixMalformedJson(trimmed)
        if (fixedJson.isNotEmpty()) return fixedJson

        val jsonRegex = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL)
        return jsonRegex.find(text)?.value ?: ""
    }

    private fun tryFixMalformedJson(text: String): String {
        val functionCallPattern = Regex("""(do|finish)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        val functionMatch = functionCallPattern.find(text)
        
        if (functionMatch != null) {
            val functionName = functionMatch.groupValues[1].lowercase()
            val paramsStr = functionMatch.groupValues[2]
            
            if (functionName == "finish") {
                val messagePattern = Regex("""message\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                val messageMatch = messagePattern.find(paramsStr)
                val message = messageMatch?.groupValues?.get(1) ?: paramsStr.trim().trim('"', '\'')
                return """{"_metadata": "finish", "message": "$message"}"""
            } else if (functionName == "do") {
                val action = mutableMapOf<String, Any>("_metadata" to "do")
                val paramPattern = Regex("""(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\[[^\]]+\]|\d+\.?\d*|true|false)""", RegexOption.IGNORE_CASE)
                val paramMatches = paramPattern.findAll(paramsStr)
                
                for (match in paramMatches) {
                    val key = match.groupValues[1]
                    val valueStr = match.groupValues[2].trim()
                    val value: Any = when {
                        valueStr.startsWith("[") -> {
                            val arrayValues = valueStr.substring(1, valueStr.length - 1).split(",").map { it.trim() }
                            "[" + arrayValues.joinToString(",") + "]"
                        }
                        valueStr.startsWith("\"") || valueStr.startsWith("'") -> {
                            valueStr.trim('"', '\'').replace("\\\"", "\"").replace("\\'", "'")
                        }
                        valueStr == "true" -> true
                        valueStr == "false" -> false
                        valueStr.contains(".") -> valueStr.toDoubleOrNull() ?: valueStr
                        else -> valueStr.toIntOrNull() ?: valueStr
                    }
                    action[key] = value
                }
                
                val jsonBuilder = StringBuilder("{")
                jsonBuilder.append("\"_metadata\": \"do\"")
                for ((key, value) in action) {
                    if (key == "_metadata") continue
                    jsonBuilder.append(", \"$key\": ")
                    when (value) {
                        is String -> {
                            if (value.startsWith("[")) jsonBuilder.append(value)
                            else jsonBuilder.append("\"${value.replace("\"", "\\\"")}\"")
                        }
                        is Number, is Boolean -> jsonBuilder.append(value)
                        else -> {
                            val vStr = value.toString()
                            if (vStr.startsWith("[")) jsonBuilder.append(vStr)
                            else jsonBuilder.append("\"${vStr.replace("\"", "\\\"")}\"")
                        }
                    }
                }
                jsonBuilder.append("}")
                return jsonBuilder.toString()
            }
        }
        return ""
    }
}
