package com.ghostdebugger.ai

import com.ghostdebugger.model.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class OllamaChatResponse(
    val model: String? = null,
    val message: ChatMessage,
    val done: Boolean = false
)
