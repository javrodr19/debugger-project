@file:OptIn(ExperimentalSerializationApi::class)

package com.ghostdebugger.ai

import com.ghostdebugger.model.ChatMessage
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    // Ollama's /api/chat streams unless the request says otherwise, the opposite default of
    // OpenAI's API. BaseAIService's Json has encodeDefaults = false, so a `stream = false`
    // passed explicitly at a call site is still indistinguishable from "not set" and gets
    // dropped from the body unless this field is forced to always encode.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stream: Boolean = false
)

@Serializable
data class OllamaChatResponse(
    val model: String? = null,
    val message: ChatMessage,
    val done: Boolean = false
)
