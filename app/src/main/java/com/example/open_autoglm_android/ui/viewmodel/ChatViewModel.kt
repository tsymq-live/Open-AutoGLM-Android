package com.example.open_autoglm_android.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.open_autoglm_android.data.ConversationRepository
import com.example.open_autoglm_android.data.InputMode
import com.example.open_autoglm_android.data.PreferencesRepository
import com.example.open_autoglm_android.data.database.Conversation
import com.example.open_autoglm_android.data.database.SavedChatMessage
import com.example.open_autoglm_android.domain.ActionExecutor
import com.example.open_autoglm_android.domain.AppRegistry
import com.example.open_autoglm_android.domain.ExecuteResult
import com.example.open_autoglm_android.network.ModelClient
import com.example.open_autoglm_android.network.dto.ChatMessage as NetworkChatMessage
import com.example.open_autoglm_android.service.AutoGLMAccessibilityService
import com.example.open_autoglm_android.service.FloatingWindowService
import com.example.open_autoglm_android.util.BitmapUtils
import com.example.open_autoglm_android.util.DeviceUtils
import com.example.open_autoglm_android.util.InputMethodHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val action: String? = null,
    val imagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // 性能指标和尺寸
    val screenshotMs: Long = 0,
    val networkMs: Long = 0,
    val executionMs: Long = 0,
    val totalMs: Long = 0,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val scaledWidth: Int = 0,
    val scaledHeight: Int = 0
)

enum class MessageRole {
    USER, ASSISTANT
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isPaused: Boolean = false,
    val error: String? = null,
    val currentApp: String? = null,
    val taskCompletedMessage: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val currentConversationId: String? = null,
    val currentConversationTitle: String? = null,
    val isDrawerOpen: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    private val preferencesRepository = PreferencesRepository(application)
    private val conversationRepository = ConversationRepository(application)
    
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private var modelClient: ModelClient? = null
    private var actionExecutor: ActionExecutor? = null
    private var currentTaskJob: Job? = null
    
    // 维护对话上下文（仅用于发送给模型，包含图片等大数据，不持久化）
    private val messageContext = mutableListOf<NetworkChatMessage>()
    
    init {
        setupFloatingWindowListeners()
        viewModelScope.launch {
            // 初始化 ModelClient
            val baseUrl = preferencesRepository.getBaseUrlSync()
            val apiKey = preferencesRepository.getApiKeySync() ?: "EMPTY"
            modelClient = ModelClient(baseUrl, apiKey)
            
            // 初始化 ActionExecutor
            AutoGLMAccessibilityService.getInstance()?.let { service ->
                actionExecutor = ActionExecutor(service)
            }
            
            // 监听对话列表变化
            launch {
                conversationRepository.conversations.collect { conversations ->
                    _uiState.value = _uiState.value.copy(conversations = conversations)
                }
            }
            
            // 监听当前对话变化
            launch {
                conversationRepository.currentConversationId.collect { conversationId ->
                    _uiState.value = _uiState.value.copy(currentConversationId = conversationId)
                    // 加载对话历史
                    loadConversationMessages(conversationId)
                }
            }

            launch {
                conversationRepository.currentConversationTitle.collect { conversationTitle ->
                    _uiState.value = _uiState.value.copy(currentConversationTitle = conversationTitle)
                }
            }
            
            // 监听当前应用变化 (UI 实时展示)
            launch {
                AutoGLMAccessibilityService.getInstance()?.currentApp?.collect { app ->
                    _uiState.value = _uiState.value.copy(currentApp = app)
                }
            }
            
            // 如果没有对话，创建一个默认对话
            val initialConversations = conversationRepository.conversations.first()
            if (initialConversations.isEmpty()) {
                conversationRepository.createConversation()
            }
        }
    }

    private fun setupFloatingWindowListeners() {
        FloatingWindowService.onStopClickListener = {
            stopTask()
        }
        FloatingWindowService.onPauseResumeClickListener = {
            togglePause()
        }
    }
    
    private fun loadConversationMessages(conversationId: String?) {
        if (conversationId == null) {
            _uiState.value = _uiState.value.copy(messages = emptyList())
            return
        }
        
        viewModelScope.launch {
            val conversationWithMessages = conversationRepository.getCurrentConversation()
            if (conversationWithMessages != null) {
                val messages = conversationWithMessages.messages.map { saved ->
                    ChatMessage(
                        id = saved.id,
                        role = if (saved.role == "USER") MessageRole.USER else MessageRole.ASSISTANT,
                        content = saved.content,
                        thinking = saved.thinking,
                        action = saved.action,
                        imagePath = saved.imagePath,
                        timestamp = saved.timestamp,
                        screenshotMs = saved.screenshotMs,
                        networkMs = saved.networkMs,
                        executionMs = saved.executionMs,
                        totalMs = saved.totalMs,
                        originalWidth = saved.originalWidth,
                        originalHeight = saved.originalHeight,
                        scaledWidth = saved.scaledWidth,
                        scaledHeight = saved.scaledHeight
                    )
                }
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }
    
    private suspend fun saveCurrentMessages() {
        val conversationId = _uiState.value.currentConversationId ?: return
        val savedMessages = _uiState.value.messages.map { msg ->
            SavedChatMessage(
                id = msg.id,
                conversationId = conversationId,
                role = msg.role.name,
                content = msg.content,
                thinking = msg.thinking,
                action = msg.action,
                imagePath = msg.imagePath,
                timestamp = msg.timestamp,
                screenshotMs = msg.screenshotMs,
                networkMs = msg.networkMs,
                executionMs = msg.executionMs,
                totalMs = msg.totalMs,
                originalWidth = msg.originalWidth,
                originalHeight = msg.originalHeight,
                scaledWidth = msg.scaledWidth,
                scaledHeight = msg.scaledHeight
            )
        }
        conversationRepository.updateConversationMessages(conversationId, savedMessages)
    }
    
    /**
     * 创建新对话
     */
    fun createNewConversation() {
        viewModelScope.launch {
            conversationRepository.createConversation()
            messageContext.clear()
        }
    }
    
    /**
     * 切换对话
     */
    fun switchConversation(conversationId: String,conversationTitle:String) {
        conversationRepository.switchConversation(conversationId)
        messageContext.clear()
        _uiState.value = _uiState.value.copy(isDrawerOpen = false, currentConversationTitle = conversationTitle)
    }
    
    /**
     * 删除对话
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
            messageContext.clear()
        }
    }
    
    /**
     * 打开/关闭侧边栏
     */
    fun toggleDrawer() {
        _uiState.value = _uiState.value.copy(isDrawerOpen = !_uiState.value.isDrawerOpen)
    }

    fun openDrawer(){
        _uiState.value =  _uiState.value.copy(isDrawerOpen = true)
    }
    
    fun closeDrawer() {
        _uiState.value = _uiState.value.copy(isDrawerOpen = false)
    }
    
    fun sendMessage(userInput: String) {
        if (userInput.isBlank() || _uiState.value.isLoading) return
        
        val accessibilityService = AutoGLMAccessibilityService.getInstance()
        if (accessibilityService == null) {
            _uiState.value = _uiState.value.copy(
                error = "无障碍服务未启用，请前往设置开启"
            )
            return
        }
        
        // 清空当前对话的消息（开始新任务）
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            taskCompletedMessage = null,
            error = null,
            isPaused = false
        )
        FloatingWindowService.getInstance()?.updatePauseStatus(false)
        
        val userMessage = ChatMessage(
            id = System.currentTimeMillis().toString(),
            role = MessageRole.USER,
            content = userInput
        )
        
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage,
            isLoading = true,
            error = null
        )
        
        currentTaskJob = viewModelScope.launch {
            try {
                // 尝试自动切换输入法
                if (preferencesRepository.getInputModeSync() == InputMode.IME) {
                    InputMethodHelper.switchToMyInputMethod(getApplication())
                }

                AppRegistry.initialize(getApplication())
                
                val baseUrl = preferencesRepository.getBaseUrlSync()
                val apiKey = preferencesRepository.getApiKeySync() ?: "EMPTY"
                val modelName = preferencesRepository.getModelNameSync()
                
                if (apiKey == "EMPTY" || apiKey.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "请先在设置页面配置 API Key"
                    )
                    return@launch
                }
                
                modelClient = ModelClient(baseUrl, apiKey)
                actionExecutor = ActionExecutor(accessibilityService)
                
                messageContext.clear()
                
                // 保存用户初始消息
                saveCurrentMessages()
                
                // 执行任务循环
                executeTaskLoop(userInput, modelName)
                
            } catch (e: CancellationException) {
                Log.d("ChatViewModel", "任务已取消")
                FloatingWindowService.getInstance()?.updateStatus("已停止", 0, "用户手动停止")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "错误: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
                currentTaskJob = null
            }
        }
    }

    fun stopTask() {
        currentTaskJob?.cancel()
        currentTaskJob = null
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isPaused = false,
            error = "任务已手动停止"
        )
        FloatingWindowService.getInstance()?.updatePauseStatus(false)
    }

    fun togglePause() {
        val newState = !_uiState.value.isPaused
        _uiState.value = _uiState.value.copy(isPaused = newState)
        FloatingWindowService.getInstance()?.updatePauseStatus(newState)
    }

    fun restartTask() {
        val firstUserMessage = _uiState.value.messages.firstOrNull { it.role == MessageRole.USER }
        if (firstUserMessage != null) {
            val content = firstUserMessage.content
            stopTask()
            sendMessage(content)
        }
    }
    
    private suspend fun executeTaskLoop(userPrompt: String, modelName: String) {
        val accessibilityService = AutoGLMAccessibilityService.getInstance() ?: return
        val client = modelClient ?: return
        val executor = actionExecutor ?: return
        
        var stepCount = 0
        val maxSteps = 50
        var retryCount = 0

        val compressionEnabled = preferencesRepository.getImageCompressionEnabledSync()
        val compressionLevel = if (compressionEnabled) preferencesRepository.getImageCompressionLevelSync() else 80
        val scalingEnabled = preferencesRepository.getImageScalingEnabledSync()
        val scalingRatio = if (scalingEnabled) preferencesRepository.getImageScalingRatioSync() / 100f else 1.0f

        // 获取模型参数
        val maxTokens = preferencesRepository.getMaxTokensSync()
        val temperature = preferencesRepository.getTemperatureSync()
        val topP = preferencesRepository.getTopPSync()
        val frequencyPenalty = preferencesRepository.getFrequencyPenaltySync()
        
        while (stepCount < maxSteps) {
            val stepStartTime = System.currentTimeMillis()
            
            while (_uiState.value.isPaused) {
                delay(500)
                yield()
            }

            Log.d("ChatViewModel", "执行步骤 $stepCount")
            FloatingWindowService.getInstance()?.updateStatus("执行中", stepCount, "正在检测当前状态...")

            val currentApp = accessibilityService.safeCurrentApp
            val myPackageName = getApplication<Application>().packageName
            val isAutoGLMForeground = currentApp == myPackageName

            if (isAutoGLMForeground && stepCount > 0) {
                FloatingWindowService.getInstance()?.updateStatus("执行中", stepCount, "等待切回目标应用...")
                delay(2000)
                continue
            }
            
            val screenshotStartTime = System.currentTimeMillis()
            val originalScreenshot = if (isAutoGLMForeground) null else accessibilityService.takeScreenshotSuspend()
            val screenshotDuration = System.currentTimeMillis() - screenshotStartTime
            
            if (originalScreenshot == null && !isAutoGLMForeground) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "无法获取屏幕截图")
                return
            }
            
            val displayMetrics = getApplication<Application>().resources.displayMetrics
            val realWidth = originalScreenshot?.width ?: displayMetrics.widthPixels
            val realHeight = originalScreenshot?.height ?: displayMetrics.heightPixels
            
            val modelScreenshot = if (originalScreenshot != null && scalingEnabled && scalingRatio < 1.0f) {
                BitmapUtils.scaleBitmap(originalScreenshot, scalingRatio)
            } else {
                originalScreenshot
            }
            
            val scaledWidth = modelScreenshot?.width ?: 0
            val scaledHeight = modelScreenshot?.height ?: 0

            // 构建消息上下文
            if (stepCount == 0) {
                if (messageContext.isEmpty()) messageContext.add(client.createSystemMessage())
                messageContext.add(client.createUserMessage(userPrompt, modelScreenshot, currentApp, compressionLevel))
            } else {
                messageContext.add(client.createScreenInfoMessage(modelScreenshot, currentApp, compressionLevel))
            }
            
            FloatingWindowService.getInstance()?.updateStatus("执行中", stepCount, "调用模型...")
            
            val networkStartTime = System.currentTimeMillis()
            val response = client.request(
                messages = messageContext.toList(),
                modelName = modelName,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                frequencyPenalty = frequencyPenalty
            )
            val networkDuration = System.currentTimeMillis() - networkStartTime
            
            messageContext.add(client.createAssistantMessage(response.thinking, response.action))
            
            // 节省 token
            if (messageContext.size >= 2) {
                val lastUserMessageIndex = messageContext.size - 2
                val lastUserMessage = messageContext[lastUserMessageIndex]
                if (lastUserMessage.role == "user") {
                    messageContext[lastUserMessageIndex] = client.removeImagesFromMessage(lastUserMessage)
                }
            }
            
            val isFinishAction = response.action.contains("\"_metadata\":\"finish\"") ||
                response.action.contains("\"_metadata\": \"finish\"") ||
                response.action.lowercase().contains("finish(")
            
            FloatingWindowService.getInstance()?.updateStatus("执行中", stepCount, "执行动作...")
            
            val executionStartTime = System.currentTimeMillis()
            val result = actionExecutor?.execute(response.action, realWidth, realHeight) ?: ExecuteResult(false, "ActionExecutor is null")
            val executionDuration = System.currentTimeMillis() - executionStartTime
            
            // 生成标记过的截图
            var savedImagePath: String? = null
            if (result.success && originalScreenshot != null && result.actionDetail != null) {
                val detail = result.actionDetail
                var markedBitmap: Bitmap? = null
                when (detail.type) {
                    "tap", "longpress", "doubletap", "type" -> if (detail.x1 != null && detail.y1 != null) markedBitmap = BitmapUtils.drawTapMarker(originalScreenshot, detail.x1, detail.y1)
                    "swipe" -> if (detail.x1 != null && detail.y1 != null && detail.x2 != null && detail.y2 != null) markedBitmap = BitmapUtils.drawSwipeMarker(originalScreenshot, detail.x1, detail.y1, detail.x2, detail.y2)
                }
                savedImagePath = if (markedBitmap != null) {
                    val path = BitmapUtils.saveBitmap(getApplication(), markedBitmap)
                    markedBitmap.recycle()
                    path
                } else BitmapUtils.saveBitmap(getApplication(), originalScreenshot)
            }
            
            if (modelScreenshot != null && modelScreenshot != originalScreenshot) modelScreenshot.recycle()

            val stepTotalDuration = System.currentTimeMillis() - stepStartTime

            // 添加助手消息到UI，包含性能和尺寸指标
            val assistantMessage = ChatMessage(
                id = "${System.currentTimeMillis()}_$stepCount",
                role = MessageRole.ASSISTANT,
                content = response.action,
                thinking = response.thinking,
                action = response.action,
                imagePath = savedImagePath,
                screenshotMs = screenshotDuration,
                networkMs = networkDuration,
                executionMs = executionDuration,
                totalMs = stepTotalDuration,
                originalWidth = realWidth,
                originalHeight = realHeight,
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight
            )
            
            _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + assistantMessage)
            saveCurrentMessages()

            if (isFinishAction) {
                val completionMessage = extractFinishMessage(response.action) ?: result.message ?: resultMessageFallback(response.action)
                FloatingWindowService.getInstance()?.updateStatus("已完成", stepCount, completionMessage)
                _uiState.value = _uiState.value.copy(isLoading = false, taskCompletedMessage = completionMessage)
                executor.bringAppToForeground()
                return
            }
            
            if (result.message != null && (result.message!!.contains("完成") || result.message!!.contains("finish"))) {
                val completionMessage = result.message ?: "任务已完成"
                FloatingWindowService.getInstance()?.updateStatus("已完成", stepCount, completionMessage)
                _uiState.value = _uiState.value.copy(isLoading = false, taskCompletedMessage = completionMessage)
                executor.bringAppToForeground()
                return
            }
            
            if (!result.success) {
                retryCount++
                if (retryCount >= 10) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message ?: "执行动作失败")
                    executor.bringAppToForeground()
                    return
                }
                delay(800)
                continue
            } else {
                retryCount = 0
            }
            
            delay(1000)
            stepCount++
        }
        
        FloatingWindowService.getInstance()?.updateStatus("已停止", stepCount, "达到最大步数限制")
        _uiState.value = _uiState.value.copy(isLoading = false, error = "达到最大步数限制")
        executor.bringAppToForeground()
    }
    
    fun getFullPromptLog(): String {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return ""
        
        return buildString {
            messages.forEach { msg ->
                append("[${msg.role}]:\n")
                if (!msg.thinking.isNullOrBlank()) {
                    append("<thinking>\n${msg.thinking}\n</thinking>\n")
                }
                append(msg.content)
                
                // 如果是助手消息，显示指标
                if (msg.role == MessageRole.ASSISTANT && msg.totalMs > 0) {
                    append("\n\n[METRICS]:")
                    if (msg.originalWidth > 0) {
                        append("\n- Image: ${msg.originalWidth}x${msg.originalHeight}")
                        if (msg.scaledWidth > 0 && msg.scaledWidth != msg.originalWidth) {
                            append(" (Scaled: ${msg.scaledWidth}x${msg.scaledHeight})")
                        }
                    }
                    append("\n- Screenshot: ${msg.screenshotMs}ms")
                    append("\n- Network: ${msg.networkMs}ms")
                    append("\n- Execution: ${msg.executionMs}ms")
                    append("\n- Total: ${msg.totalMs}ms")
                }
                append("\n\n" + "-".repeat(20) + "\n\n")
            }
        }
    }

    private fun extractFinishMessage(action: String): String? {
        val jsonPattern = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
        val jsonMatch = jsonPattern.find(action)
        if (jsonMatch != null) return jsonMatch.groupValues[1]
        val funcPattern = Regex("finish\\s*\\(\\s*message\\s*=\\s*['\"]([^'\"]+)['\"]\\s*\\)", RegexOption.IGNORE_CASE)
        val funcMatch = funcPattern.find(action)
        if (funcMatch != null) return funcMatch.groupValues[1]
        return null
    }
    
    private fun resultMessageFallback(action: String): String {
        return if (action.length > 80) action.take(80) + "..." else action.ifBlank { "任务已完成" }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun clearTaskCompletedMessage() {
        _uiState.value = _uiState.value.copy(taskCompletedMessage = null)
    }
    
    fun clearMessages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(messages = emptyList(), error = null, taskCompletedMessage = null)
            messageContext.clear()
            saveCurrentMessages()
        }
    }
    
    fun refreshModelClient() {
        viewModelScope.launch {
            val baseUrl = preferencesRepository.getBaseUrlSync()
            val apiKey = preferencesRepository.getApiKeySync() ?: "EMPTY"
            modelClient = ModelClient(baseUrl, apiKey)
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentTaskJob?.cancel()
        conversationRepository.cleanup()
    }
}
