package org.dropProject.dropProjectPlugin.gpt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GPTResponse(
    @Json(name = "id")
    val id: String,
    @Json(name = "object")
    val objectType: String,
    @Json(name = "created")
    val created: Long,
    @Json(name = "model")
    val model: String,
    @Json(name = "choices")
    val choices: List<Choice>,
    @Json(name = "usage")
    val usage: Usage
)

@JsonClass(generateAdapter = true)
data class Choice(
    @Json(name = "index")
    val index: Int,
    @Json(name = "message")
    val message: Message,
    @Json(name = "finish_reason")
    val finishReason: String
)

@JsonClass(generateAdapter = true)
data class Message(
    @Json(name = "role")
    val role: String,
    @Json(name = "content")
    var content: String
)

@JsonClass(generateAdapter = true)
data class Usage(
    @Json(name = "prompt_tokens")
    val promptTokens: Int,
    @Json(name = "completion_tokens")
    val completionTokens: Int,
    @Json(name = "total_tokens")
    val totalTokens: Int
)