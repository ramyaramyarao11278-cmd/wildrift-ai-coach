package com.wildrift.aicoach.config

import android.content.Context
import android.content.SharedPreferences

enum class AnalysisMode(val title: String, val badge: String) {
    BP_PHASE("选人 BP 分析", "⚡ BP选人"),
    LOADING_PHASE("加载全阵容分析", "⚔️ 阵容深析"),
    SCOREBOARD_PHASE("局内计分板/出装", "🛡️ 局内出装")
}

data class ApiPreset(
    val name: String,
    val baseUrl: String,
    val defaultVisionModel: String,
    val defaultReasoningModel: String,
    val hint: String
)

object CoachPrefs {
    private const val PREF_NAME = "wildrift_ai_coach_prefs"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_VISION_MODEL = "vision_model_name"
    private const val KEY_REASONING_MODEL = "reasoning_model_name"
    private const val KEY_TWO_STAGE_ENABLED = "two_stage_enabled"
    private const val KEY_USER_ROLE_HINT = "user_role_hint"
    private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
    private const val KEY_IMAGE_MAX_DIM = "image_max_dim"
    private const val KEY_STREAM_ENABLED = "stream_enabled"

    private val DEFAULT_BUILTIN_API_KEY: String
        get() = "ghn4YQgApc2CnYbr0AMqj055in7zzoeZdaxHef3BEBKL6NR8bA.QA".reversed()

    val PRESETS = listOf(
        ApiPreset(
            name = "Google Gemini 双阶段 (Flash提JSON + Argon深度推理)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultVisionModel = "gemini-2.5-flash",
            defaultReasoningModel = "gemini-4-argon",
            hint = "阶段1用 gemini-2.5-flash 极速识图输出结构化 JSON，阶段2交由 gemini-4-argon 深度推理（若遇429限流自动无缝降级至 gemini-3-flash-preview）"
        ),
        ApiPreset(
            name = "Google Gemini 3.1 Pro 双阶段 (Flash提JSON + 3.1 Pro推理)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultVisionModel = "gemini-3-flash-preview",
            defaultReasoningModel = "gemini-3.1-pro-preview",
            hint = "阶段1使用 gemini-3-flash-preview 提取 JSON，阶段2使用 gemini-3.1-pro-preview 深度分析"
        ),
        ApiPreset(
            name = "通义千问 双阶段 (Qwen-VL提JSON + Qwen-Max/DeepSeek推理)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultVisionModel = "qwen-vl-max",
            defaultReasoningModel = "deepseek-r1",
            hint = "阿里云百炼兼容接口：用 qwen-vl-max 提取阵容 JSON，用 deepseek-r1 或 qwen-max 做深度推理"
        ),
        ApiPreset(
            name = "豆包视觉 + DeepSeek 双阶段 (火山引擎 Ark)",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultVisionModel = "doubao-1-5-vision-pro-32k-250115",
            defaultReasoningModel = "deepseek-r1-250120",
            hint = "火山方舟：视觉模型提取 JSON + DeepSeek-R1 深度推理"
        ),
        ApiPreset(
            name = "OpenAI / 聚合中转站",
            baseUrl = "https://api.openai.com/v1",
            defaultVisionModel = "gpt-4o-mini",
            defaultReasoningModel = "gpt-4o",
            hint = "支持任意兼容 OpenAI /v1/chat/completions 格式的中转站"
        )
    )

    const val DEFAULT_SYSTEM_PROMPT = """你是一位《英雄联盟手游》（League of Legends: Wild Rift）国服顶尖王者教练与战术分析师。
你将收到由前序视觉模型从玩家手机画面中提取出的【结构化阵容 JSON 数据】。
请基于这份 JSON 数据进行深度、专业、实战导向的阵容博弈推演。
排版要求：
1. 使用清晰的【模块标题】与短句要点（每条不超过2句话），方便玩家在手机横屏小悬浮窗内快速扫读。
2. 严禁长篇大论的废话套话，直接给明确结论（先杀谁、谁克制谁、出什么针对装、带什么技能、前中后期怎么运营）。"""

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, PRESETS[0].baseUrl)?.trim() ?: PRESETS[0].baseUrl

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE_URL, value.trim()).apply()
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, DEFAULT_BUILTIN_API_KEY)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: DEFAULT_BUILTIN_API_KEY

    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API_KEY, value.trim()).apply()
    }

    fun getVisionModel(context: Context): String =
        prefs(context).getString(KEY_VISION_MODEL, PRESETS[0].defaultVisionModel)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: PRESETS[0].defaultVisionModel

    fun setVisionModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VISION_MODEL, value.trim()).apply()
    }

    fun getReasoningModel(context: Context): String =
        prefs(context).getString(KEY_REASONING_MODEL, PRESETS[0].defaultReasoningModel)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: PRESETS[0].defaultReasoningModel

    fun setReasoningModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_REASONING_MODEL, value.trim()).apply()
    }

    fun isTwoStageEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TWO_STAGE_ENABLED, true)

    fun setTwoStageEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TWO_STAGE_ENABLED, enabled).apply()
    }

    fun getUserRoleHint(context: Context): String =
        prefs(context).getString(KEY_USER_ROLE_HINT, "")?.trim() ?: ""

    fun setUserRoleHint(context: Context, value: String) {
        prefs(context).edit().putString(KEY_USER_ROLE_HINT, value.trim()).apply()
    }

    fun getSystemPrompt(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: DEFAULT_SYSTEM_PROMPT

    fun setSystemPrompt(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, value.trim()).apply()
    }

    fun getImageMaxDim(context: Context): Int =
        prefs(context).getInt(KEY_IMAGE_MAX_DIM, 1280)

    fun setImageMaxDim(context: Context, value: Int) {
        prefs(context).edit().putString(KEY_IMAGE_MAX_DIM, null)
        prefs(context).edit().putInt(KEY_IMAGE_MAX_DIM, value.coerceIn(720, 1920)).apply()
    }

    fun isStreamEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM_ENABLED, true)

    fun setStreamEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_ENABLED, enabled).apply()
    }

    /**
     * Stage 1 Prompt: Vision Model extracts pure structured JSON from the screenshot.
     */
    fun buildStage1JsonExtractionPrompt(mode: AnalysisMode): String {
        return """
你是《英雄联盟手游》（Wild Rift）画面结构化识别器。请仔细观察截图中的英雄头像、皮肤下方的英雄名称、分路图标（单人路/打野/中路/双人路/辅助）、召唤师技能、符文、Ban位或计分板装备，仅输出一个合法的 JSON 对象（不要输出任何解释文字，不要带 markdown 代码块包裹）。

当前期望检测阶段：${mode.name} (${mode.title})

请严格按以下 JSON 结构输出（未识别或未选出的字段填空字符串或空数组）：
{
  "detected_screen": "BP选人界面 | 加载Loading界面 | 局内计分板",
  "ally_team": [
    {"slot": 1, "lane": "分路", "hero": "英雄名", "spells": ["技能1", "技能2"], "keystone": "主系符文", "kda": "", "gold": "", "items": []}
  ],
  "enemy_team": [
    {"slot": 1, "lane": "分路", "hero": "英雄名", "spells": ["技能1", "技能2"], "keystone": "主系符文", "kda": "", "gold": "", "items": []}
  ],
  "ally_bans": ["禁用英雄1", "禁用英雄2"],
  "enemy_bans": ["禁用英雄1", "禁用英雄2"],
  "unpicked_slots_note": "例如：我方4、5楼未选，敌方5楼未选"
}
        """.trimIndent()
    }

    /**
     * Stage 2 Prompt: Deep Reasoning Model (e.g. gemini-4-argon) analyzes the extracted JSON.
     */
    fun buildStage2ReasoningPrompt(
        context: Context,
        mode: AnalysisMode,
        extractedJson: String,
        extraQuestion: String = ""
    ): String {
        val roleHint = getUserRoleHint(context)
        val roleSection = if (roleHint.isNotEmpty()) {
            "\n【玩家个人偏好/主玩位置】：$roleHint（请重点围绕该位置视角给建议）\n"
        } else {
            ""
        }
        val extraSection = if (extraQuestion.isNotBlank()) {
            "\n【玩家当前追问重点】：${extraQuestion.trim()}\n"
        } else {
            ""
        }

        val taskInstruction = when (mode) {
            AnalysisMode.BP_PHASE -> """
基于以下从《英雄联盟手游》画面识别出的【实时阵容 JSON 配置】，请进行深度 BP 博弈分析：
1. ⚠️【我方阵容缺口诊断】：一针见血指出我方目前缺什么（如：纯菜刀队缺AP、缺前排坦度、缺硬控先手、缺清线或反手能力）。
2. 🎯【Counter 选人推荐】：针对敌方已锁定的核心英雄，推荐 2~3 个当前手游最好打他们的克制英雄，并说明克制机制。
3. 🔥【符文与召唤师技能】：建议我方关键位置携带的召唤师技能（如是否必须带虚弱/净化/点燃）与核心基石符文微调。
            """.trimIndent()

            AnalysisMode.LOADING_PHASE -> """
基于以下从《英雄联盟手游》加载界面识别出的【5v5 全阵容 JSON 配置】，请进行深度对局推演：
1. 📋【5v5 分路对位优劣】：快速点出哪条路是优势线、哪条路抗压，帮打野明确前期围绕哪条路打。
2. 📈【强势期与胜率曲线】：对比双方前期（1级野区/首条小龙与先锋团）、中期（2件套）、后期大团的强度，明确告诉我方该打快节奏进攻还是拖发育。
3. 🏆【核心赢点与团战打法】：我方团战应该怎么打（强开秒谁、保哪个C位反打、还是四一分带拉扯）？第一集火目标是谁？
4. 🛡️【针对出装与防坑指南】：明确指出这把必须早做哪些针对装（如：重伤、水银饰带、巨蛇之牙破盾、冰心/兰顿、自然之力等）及最需提防的关键技能。
            """.trimIndent()

            AnalysisMode.SCOREBOARD_PHASE -> """
基于以下从《英雄联盟手游》局内计分板识别出的【实时战况 JSON 配置】，请给出即时决策：
1. 💰【局势与大爹识别】：指出敌方目前谁发育最好、威胁最大。
2. 🛡️【下一件针对装建议】：针对敌方最肥的点和伤害构成，推荐我方立刻补出什么防装或针对性输出装。
3. 🧭【当前运营节奏】：现在应该抱团抢资源抓单，还是避战守塔换线发育？团战优先限制谁？
            """.trimIndent()
        }

        return """
$taskInstruction
$roleSection$extraSection
【当前局内结构化阵容 JSON 数据】：
$extractedJson
        """.trimIndent()
    }
}
