package com.wildrift.aicoach.network

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object VisionLlmClient {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface StreamCallback {
        fun onPartialUpdate(fullTextSoFar: String)
        fun onComplete(finalText: String)
        fun onError(errorMessage: String)
    }

    interface TwoStageCallback {
        fun onStage1JsonExtracted(extractedJson: String, visionModelUsed: String)
        fun onStage2ReasoningPartial(
            extractedJson: String,
            reasoningTextSoFar: String,
            reasoningModelUsed: String
        )
        fun onStage2Complete(
            extractedJson: String,
            finalReasoningText: String,
            reasoningModelUsed: String
        )
        fun onError(stageName: String, errorMessage: String)
    }

    fun normalizeEndpoint(rawBaseUrl: String): String {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed
        }
        return "$trimmed/chat/completions"
    }

    /**
     * Test API connectivity for both Stage 1 (Vision) and Stage 2 (Reasoning).
     */
    fun testConnection(
        baseUrl: String,
        apiKey: String,
        visionModel: String,
        reasoningModel: String,
        callback: StreamCallback
    ) {
        executor.execute {
            try {
                if (apiKey.isBlank()) {
                    postError(callback, "请先填写 API Key")
                    return@execute
                }
                val endpoint = normalizeEndpoint(baseUrl)

                // 1. Test Vision Model
                val visionPayload = buildTextOnlyPayload(
                    modelName = visionModel,
                    systemPrompt = "",
                    userPrompt = "Reply in 5 words: Stage1 Vision Ready.",
                    stream = false,
                    maxTokens = 256
                )
                val vResp = executeNonStreamingRequest(endpoint, apiKey, visionPayload, timeoutMs = 12000)

                // 2. Test Reasoning Model (e.g. gemini-4-argon)
                var rStatus: String
                try {
                    val reasonPayload = buildTextOnlyPayload(
                        modelName = reasoningModel,
                        systemPrompt = "",
                        userPrompt = "Reply in 5 words: Stage2 Argon Ready.",
                        stream = false,
                        maxTokens = 256
                    )
                    val rResp = executeNonStreamingRequest(endpoint, apiKey, reasonPayload, timeoutMs = 12000)
                    rStatus = "✅ 阶段2 [$reasoningModel] 正常 ($rResp)"
                } catch (e: Exception) {
                    rStatus = "⚠️ 阶段2 [$reasoningModel] 当前受限 (${e.message?.take(60)})，实战时将自动无缝回退至可用推理模型"
                }

                postComplete(
                    callback,
                    "✅ 阶段1 [$visionModel] 正常 ($vResp)\n$rStatus"
                )
            } catch (e: Exception) {
                postError(callback, "连接失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Full Two-Stage Pipeline:
     * Stage 1: Vision Model reads screenshot -> outputs structured JSON config.
     * Stage 2: Reasoning Model (e.g. gemini-4-argon) reads JSON config -> streams deep tactical analysis.
     */
    fun runTwoStagePipeline(
        baseUrl: String,
        apiKey: String,
        visionModel: String,
        reasoningModel: String,
        stage1Prompt: String,
        systemPrompt: String,
        stage2PromptBuilder: (extractedJson: String) -> String,
        base64Jpeg: String,
        useStream: Boolean,
        callback: TwoStageCallback
    ) {
        executor.execute {
            val endpoint = normalizeEndpoint(baseUrl)
            if (apiKey.isBlank()) {
                mainHandler.post {
                    callback.onError("配置检查", "未配置 API Key，请返回主界面填写。")
                }
                return@execute
            }

            // --- STAGE 1: Vision Model -> JSON Configuration ---
            val extractedJson: String
            var actualVisionModel = visionModel
            try {
                val stage1Payload = buildVisionPayload(
                    modelName = visionModel,
                    systemPrompt = "你是一个只输出合法 JSON 的《英雄联盟手游》阵容识别器。",
                    userPrompt = stage1Prompt,
                    base64Jpeg = base64Jpeg,
                    stream = false
                )
                val rawStage1 = try {
                    executeNonStreamingRequest(endpoint, apiKey, stage1Payload, timeoutMs = 25000)
                } catch (firstErr: Exception) {
                    // Fallback if custom vision model name had an issue
                    if (visionModel != "gemini-2.5-flash" && endpoint.contains("googleapis.com")) {
                        actualVisionModel = "gemini-2.5-flash"
                        val fallbackPayload = buildVisionPayload(
                            modelName = actualVisionModel,
                            systemPrompt = "你是一个只输出合法 JSON 的《英雄联盟手游》阵容识别器。",
                            userPrompt = stage1Prompt,
                            base64Jpeg = base64Jpeg,
                            stream = false
                        )
                        executeNonStreamingRequest(endpoint, apiKey, fallbackPayload, timeoutMs = 25000)
                    } else {
                        throw firstErr
                    }
                }
                extractedJson = cleanAndPrettyJson(rawStage1)
                mainHandler.post {
                    callback.onStage1JsonExtracted(extractedJson, actualVisionModel)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback.onError(
                        "阶段1 视觉提取 JSON ($visionModel)",
                        e.message ?: e.javaClass.simpleName
                    )
                }
                return@execute
            }

            // --- STAGE 2: Reasoning Model (e.g. gemini-4-argon) -> Deep Tactical Analysis ---
            val stage2UserPrompt = stage2PromptBuilder(extractedJson)
            executeStage2ReasoningInternal(
                endpoint = endpoint,
                apiKey = apiKey,
                preferredReasoningModel = reasoningModel,
                fallbackVisionModel = actualVisionModel,
                systemPrompt = systemPrompt,
                stage2UserPrompt = stage2UserPrompt,
                extractedJson = extractedJson,
                useStream = useStream,
                callback = callback
            )
        }
    }

    /**
     * Re-runs ONLY Stage 2 (e.g. gemini-4-argon) using user-edited JSON from the floating window!
     */
    fun runStage2OnlyFromJson(
        baseUrl: String,
        apiKey: String,
        reasoningModel: String,
        fallbackModel: String,
        systemPrompt: String,
        stage2UserPrompt: String,
        extractedJson: String,
        useStream: Boolean,
        callback: TwoStageCallback
    ) {
        executor.execute {
            val endpoint = normalizeEndpoint(baseUrl)
            executeStage2ReasoningInternal(
                endpoint = endpoint,
                apiKey = apiKey,
                preferredReasoningModel = reasoningModel,
                fallbackVisionModel = fallbackModel,
                systemPrompt = systemPrompt,
                stage2UserPrompt = stage2UserPrompt,
                extractedJson = extractedJson,
                useStream = useStream,
                callback = callback
            )
        }
    }

    private fun executeStage2ReasoningInternal(
        endpoint: String,
        apiKey: String,
        preferredReasoningModel: String,
        fallbackVisionModel: String,
        systemPrompt: String,
        stage2UserPrompt: String,
        extractedJson: String,
        useStream: Boolean,
        callback: TwoStageCallback
    ) {
        // Candidate chain: try user's chosen reasoning model (e.g. gemini-4-argon) first,
        // and if rate-limited (429) or unavailable on current key tier, gracefully fall back
        val candidateModels = linkedSetOf(preferredReasoningModel).apply {
            if (endpoint.contains("googleapis.com")) {
                add("gemini-3-flash-preview")
                add("gemini-2.5-flash")
            }
            add(fallbackVisionModel)
        }.toList()

        var lastError: Exception? = null
        for ((index, candidate) in candidateModels.withIndex()) {
            try {
                val modelLabel = if (index == 0) {
                    candidate
                } else {
                    "$candidate (因 $preferredReasoningModel 429限流自动接管)"
                }

                val payload = buildTextOnlyPayload(
                    modelName = candidate,
                    systemPrompt = systemPrompt,
                    userPrompt = stage2UserPrompt,
                    stream = useStream,
                    maxTokens = 4096
                )

                if (useStream) {
                    executeStage2Streaming(
                        endpoint = endpoint,
                        apiKey = apiKey,
                        payload = payload,
                        extractedJson = extractedJson,
                        modelLabel = modelLabel,
                        callback = callback
                    )
                } else {
                    val result = executeNonStreamingRequest(endpoint, apiKey, payload, timeoutMs = 45000)
                    mainHandler.post {
                        callback.onStage2Complete(extractedJson, result, modelLabel)
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                // Continue to next candidate in chain
            }
        }

        mainHandler.post {
            callback.onError(
                "阶段2 深度推理 ($preferredReasoningModel)",
                lastError?.message ?: "未知错误"
            )
        }
    }

    private fun cleanAndPrettyJson(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.removeSuffix("```").trim()
            }
        }
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1)
        }
        return try {
            JSONObject(cleaned).toString(2)
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun buildTextOnlyPayload(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        stream: Boolean,
        maxTokens: Int
    ): JSONObject {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userPrompt)
        })
        return JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", 0.35)
            put("max_tokens", maxTokens)
            put("stream", stream)
        }
    }

    private fun buildVisionPayload(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String,
        stream: Boolean
    ): JSONObject {
        val messages = JSONArray()

        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        val userContentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userPrompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Jpeg")
                })
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContentArray)
        })

        return JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", 0.15)
            put("max_tokens", 2048)
            put("stream", stream)
        }
    }

    private fun executeStage2Streaming(
        endpoint: String,
        apiKey: String,
        payload: JSONObject,
        extractedJson: String,
        modelLabel: String,
        callback: TwoStageCallback
    ) {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 45000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errBody = readErrorStream(conn)
            throw RuntimeException("HTTP $code: $errBody")
        }

        val contentType = conn.getHeaderField("Content-Type") ?: ""
        val sb = StringBuilder()

        BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
            if (contentType.contains("application/json", ignoreCase = true)) {
                val fullJson = reader.readText()
                val parsed = parseStandardCompletionJson(fullJson)
                mainHandler.post {
                    callback.onStage2Complete(extractedJson, parsed, modelLabel)
                }
                return
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: continue
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue

                val dataPart = trimmed.removePrefix("data:").trim()
                if (dataPart == "[DONE]") {
                    break
                }

                try {
                    val chunkObj = JSONObject(dataPart)
                    val choices = chunkObj.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val delta = firstChoice.optJSONObject("delta")
                        if (delta != null) {
                            val contentPiece = delta.optString("content", "")
                            if (contentPiece.isNotEmpty() && contentPiece != "null") {
                                sb.append(contentPiece)
                                val currentText = sb.toString()
                                mainHandler.post {
                                    callback.onStage2ReasoningPartial(
                                        extractedJson,
                                        currentText,
                                        modelLabel
                                    )
                                }
                            }
                        } else {
                            val msg = firstChoice.optJSONObject("message")
                            val fullContent = msg?.optString("content", "") ?: ""
                            if (fullContent.isNotEmpty()) {
                                sb.append(fullContent)
                                val currentText = sb.toString()
                                mainHandler.post {
                                    callback.onStage2ReasoningPartial(
                                        extractedJson,
                                        currentText,
                                        modelLabel
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val finalOut = sb.toString().trim()
        if (finalOut.isEmpty()) {
            throw RuntimeException("模型返回内容为空")
        }
        mainHandler.post {
            callback.onStage2Complete(extractedJson, finalOut, modelLabel)
        }
    }

    private fun executeNonStreamingRequest(
        endpoint: String,
        apiKey: String,
        payload: JSONObject,
        timeoutMs: Int = 25000
    ): String {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errBody = readErrorStream(conn)
            throw RuntimeException("HTTP $code: $errBody")
        }

        val responseBody = BufferedReader(
            InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)
        ).use { it.readText() }

        return parseStandardCompletionJson(responseBody)
    }

    private fun parseStandardCompletionJson(rawJson: String): String {
        val root = JSONObject(rawJson)
        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.getJSONObject(0).optJSONObject("message")
            val content = message?.optString("content", "") ?: ""
            if (content.isNotBlank()) {
                return content.trim()
            }
        }
        throw RuntimeException("响应解析失败: $rawJson")
    }

    private fun readErrorStream(conn: HttpURLConnection): String {
        return try {
            val stream = conn.errorStream ?: conn.inputStream
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use {
                it.readText().take(400)
            }
        } catch (e: Exception) {
            conn.responseMessage ?: "未知网络错误"
        }
    }

    private fun postComplete(callback: StreamCallback, text: String) {
        mainHandler.post { callback.onComplete(text) }
    }

    private fun postError(callback: StreamCallback, error: String) {
        mainHandler.post { callback.onError(error) }
    }
}
