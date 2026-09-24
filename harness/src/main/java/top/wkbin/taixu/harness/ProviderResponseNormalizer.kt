package top.wkbin.taixu.harness

import kotlinx.serialization.json.Json

/** Protocol normalization boundary shared by the main loop and independently testable. */
class ProviderResponseNormalizer(
    private val json: Json,
) {
    fun normalize(result: ChatResult, rawText: String, toolsEnabled: Boolean): NormalizedProviderResponse {
        // Structured native calls are authoritative: skip the textual codec so the display text
        // stays byte-identical with rawText and stray markers in the content do not get stripped
        // as if they were executed. The textual branch only runs as a fallback.
        if (!toolsEnabled || result.toolCalls.isNotEmpty()) {
            return NormalizedProviderResponse(
                result = result,
                rawText = rawText,
                displayText = rawText,
                toolCalls = result.toolCalls,
                textToolCallCount = 0,
                invalidMarkerCount = 0,
                hasUnresolvedMarkers = false,
            )
        }
        val textNormalization = TextToolCallCodec.normalize(json, rawText)
        if (textNormalization.calls.isNotEmpty() || textNormalization.hasUnresolvedMarkers) {
            return NormalizedProviderResponse(
                result = result,
                rawText = rawText,
                displayText = textNormalization.displayText,
                toolCalls = textNormalization.calls,
                textToolCallCount = textNormalization.calls.size,
                invalidMarkerCount = textNormalization.invalidMarkerCount,
                hasUnresolvedMarkers = textNormalization.hasUnresolvedMarkers,
            )
        }

        return NormalizedProviderResponse(
            result = result,
            rawText = rawText,
            displayText = rawText,
            toolCalls = emptyList(),
            textToolCallCount = 0,
            invalidMarkerCount = 0,
            hasUnresolvedMarkers = false,
        )
    }

}

data class NormalizedProviderResponse(
    val result: ChatResult,
    val rawText: String,
    val displayText: String,
    val toolCalls: List<ApiToolCallSpec>,
    val textToolCallCount: Int,
    val invalidMarkerCount: Int,
    val hasUnresolvedMarkers: Boolean,
) {
    /**
     * 完全空的一轮：模型没产出正文、推理，也没有（原生或文本）工具调用。
     *
     * 判据取 rawText 而非 result.content：rawText 是流式累积的真实回包，两者应当一致，
     * 但工具协议解析失败时 displayText 会被清空而 rawText 保留原文，此时不属于"空响应"。
     */
    val isBlankResponse: Boolean
        get() = rawText.isBlank() && result.reasoningContent.isNullOrBlank() && toolCalls.isEmpty()
}
