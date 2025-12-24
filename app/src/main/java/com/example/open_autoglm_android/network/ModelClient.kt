package com.example.open_autoglm_android.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.open_autoglm_android.network.dto.*
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.log

data class ModelResponse(
    val thinking: String,
    val action: String
)

class ModelClient(
    baseUrl: String,
    private val apiKey: String
) {
    private val api: AutoGLMApi
    
    init {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header("Authorization", if (apiKey.isBlank() || apiKey == "EMPTY") "Bearer EMPTY" else "Bearer $apiKey")
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        api = retrofit.create(AutoGLMApi::class.java)
    }
    
    /**
     * 请求模型（使用消息上下文）
     */
    suspend fun request(
        messages: List<ChatMessage>,
        modelName: String,
        maxTokens: Int = 3000,
        temperature: Double = 0.0,
        topP: Double = 0.85,
        frequencyPenalty: Double = 0.2
    ): ModelResponse {
        val request = ChatRequest(
            model = modelName,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            frequencyPenalty = frequencyPenalty,
            stream = false
        )
        
        val response = api.chatCompletion(request)
        
        if (response.isSuccessful && response.body() != null) {
            val responseBody = response.body()!!
            val content = responseBody.choices.firstOrNull()?.message?.content ?: ""
            return parseResponse(content)
        } else {
            throw Exception("API request failed: ${response.code()} ${response.message()}")
        }
    }
    
    /**
     * 创建系统消息
     */
    fun createSystemMessage(isVirtualDisplay: Boolean = false): ChatMessage {
        val systemPrompt = if (isVirtualDisplay) buildVirtualDisplayPrompt() else buildSystemPrompt()
        return ChatMessage(
            role = "system",
            content = listOf(ContentItem(type = "text", text = systemPrompt))
        )
    }
    
    /**
     * 创建消息的通用基础方法
     */
    private fun createMessage(
        text: String,
        screenshot: Bitmap?,
        currentApp: String?,
        quality: Int = 80
    ): ChatMessage {
        val userContent = mutableListOf<ContentItem>()
        val screenInfoJson = buildScreenInfo(currentApp)
        val fullText = if (text.isEmpty()) screenInfoJson else "$text\n\n$screenInfoJson"

        // 对齐旧项目：先放图片，再放文本
        screenshot?.let { bitmap ->
            val base64Image = bitmapToBase64(bitmap, quality)
            userContent.add(
                ContentItem(
                    type = "image_url",
                    imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64Image")
                )
            )
        }

        userContent.add(ContentItem(type = "text", text = fullText))
        return ChatMessage(role = "user", content = userContent)
    }

    /**
     * 创建用户消息
     */
    fun createUserMessage(userPrompt: String, screenshot: Bitmap?, currentApp: String?, quality: Int = 80): ChatMessage {
        return createMessage(userPrompt, screenshot, currentApp, quality)
    }
    
    /**
     * 创建屏幕信息消息
     */
    fun createScreenInfoMessage(screenshot: Bitmap?, currentApp: String?, quality: Int = 80): ChatMessage {
        return createMessage("** Screen Info **", screenshot, currentApp, quality)
    }
    
    /**
     * 创建助手消息
     */
    fun createAssistantMessage(thinking: String, action: String): ChatMessage {
        val content = "<think>$thinking</think><answer>$action</answer>"
        return ChatMessage(
            role = "assistant",
            content = listOf(ContentItem(type = "text", text = content))
        )
    }
    
    private fun buildScreenInfo(currentApp: String?): String {
        val json = JsonObject()
        json.addProperty("current_app", currentApp ?: "Unknown")
        return json.toString()
    }
    
    fun removeImagesFromMessage(message: ChatMessage): ChatMessage {
        val textOnlyContent = message.content.filter { it.type == "text" }
        return ChatMessage(
            role = message.role,
            content = textOnlyContent
        )
    }
    
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        }
    }
    
    private fun buildSystemPrompt(): String {
        Log.e("==========","普通提示词")
        return """
今天的日期是：${java.time.LocalDate.now()}

你是一个移动端任务执行智能体（AutoGLM Mobil Agent）。你当前正在通过**无障碍服务**直接操作用户的**主屏幕**。

【输出格式（必须严格遵守）】
你只能输出以下 XML 结构，不能包含任何多余文本：

<think>操作选择的简要理由摘要（不超过 20 字，不展开推理）</think>
<answer>操作指令</answer>

【操作指令白名单】
你只能在 <answer> 中输出以下指令之一，且每次只能输出一个：

- do(action="Launch", app="xxx")
- do(action="Tap", element=[x,y])
- do(action="Type", text="xxx")
- do(action="Swipe", start=[x1,y1], end=[x2,y2])
- do(action="Back")
- do(action="Home")
- do(action="Wait", duration="x seconds")
- finish(message="xxx")

【执行规则】
1. 若当前 app 不是目标 app，先执行 Launch。
2. 任务完成后必须使用 finish 结束。
""".trimIndent()
    }

    /**
     * 虚拟屏专用提示词
     */
    private fun buildVirtualDisplayPrompt(): String {
        Log.e("==========","虚拟屏专用提示词")
        return """
今天的日期是：${java.time.LocalDate.now()}

你是一个移动端任务执行智能体（AutoGLM Mobil Agent）。你当前正在通过**无障碍服务**直接操作用户的**主屏幕**。

【输出格式（必须严格遵守）】
你只能输出以下 XML 结构，不能包含任何多余文本：

<think>操作选择的简要理由摘要（不超过 20 字，不展开推理）</think>
<answer>操作指令</answer>

【操作指令白名单】
你只能在 <answer> 中输出以下指令之一，且每次只能输出一个：

- do(action="Tap", element=[x,y])
- do(action="Type", text="xxx")
- do(action="Swipe", start=[x1,y1], end=[x2,y2])
- do(action="Back")
- do(action="Home")
- do(action="Wait", duration="x seconds")
- finish(message="xxx")

【执行规则】
1. 若当前 app 不是目标 app 先找到目标 app 点击启动，严进使用Launch方法
2. 任务完成后必须使用 finish 结束。
""".trimIndent()
    }
    
    private fun parseResponse(content: String): ModelResponse {
        var thinking = ""
        var action = ""
        
        if (content.contains("<answer>")) {
            val parts = content.split("<answer>", limit = 2)
            thinking = parts[0]
                .replace("<think>", "")
                .replace("</think>", "")
                .trim()
            action = parts[1].replace("</answer>", "").trim()
        } else {
            action = content.trim()
        }
        
        return ModelResponse(thinking = thinking, action = action)
    }
    
    private fun String.ensureTrailingSlash(): String {
        return if (this.endsWith("/")) this else "$this/"
    }
}
