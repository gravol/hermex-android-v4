package com.hermex.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionSummary(
    val id: String = "",
    val title: String? = null,
    val source: String? = null,
    val model: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("tool_call_count") val toolCallCount: Int = 0,
    // Token usage for Insights aggregation (input + output per session).
    @SerialName("input_tokens") val inputTokens: Long = -1,
    @SerialName("output_tokens") val outputTokens: Long = -1,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("last_activity_at") val lastActivityAt: Double? = null,
    @SerialName("last_activity_description") val lastActivityDescription: String? = null,
    // Server-reported live flag (session.list `is_active`). Null on gateways that
    // don't send it — the home tree then falls back to the in-app chat-VM map and
    // hides the dot rather than fabricating a status.
    @SerialName("is_active") val isActive: Boolean? = null,
    val preview: String? = null,
)
