package com.ghostdebugger.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int = 4096,
    val temperature: Double = 0.3,
    val response_format: ResponseFormat? = null
)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice>,
    val usage: TokenUsage? = null
)

@Serializable
data class ChatChoice(
    val message: ChatMessage,
    val finish_reason: String? = null
)

@Serializable
data class TokenUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

data class SystemSummary(
    val overview: String,
    val keyComponents: List<String>,
    val mainFlows: List<String>,
    val risks: List<String>
)

data class WhatIfResponse(
    val analysis: String,
    val risks: List<String>,
    val recommendations: List<String>
)

data class CodeContext(
    val fileContent: String,
    val surroundingCode: String,
    val relatedFiles: List<String>
)
