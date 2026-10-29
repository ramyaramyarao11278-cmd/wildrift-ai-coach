package com.wildrift.aicoach

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wildrift.aicoach.config.CoachPrefs
import com.wildrift.aicoach.network.VisionLlmClient
import com.wildrift.aicoach.service.OverlayCoachService

class MainActivity : AppCompatActivity() {

    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etVisionModel: EditText
    private lateinit var etReasoningModel: EditText
    private lateinit var etRoleHint: EditText
    private lateinit var etSystemPrompt: EditText
    private lateinit var cbStream: CheckBox
    private lateinit var tvPresetHint: TextView
    private lateinit var tvTestStatus: TextView
    private lateinit var btnStartService: Button

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已开启！现在将请求截屏权限", Toast.LENGTH_SHORT).show()
                requestMediaProjectionPermission()
            } else {
                Toast.makeText(this, "需要授予悬浮窗权限才能在游戏上方显示战术面板", Toast.LENGTH_LONG).show()
            }
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, OverlayCoachService::class.java).apply {
                    action = OverlayCoachService.ACTION_START
                    putExtra(OverlayCoachService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayCoachService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(
                    this,
                    "✅ 双阶段 AI 战术悬浮球已启动！现在可以切入《英雄联盟手游》",
                    Toast.LENGTH_LONG
                ).show()
                updateServiceStatusUi()
            } else {
                Toast.makeText(this, "已取消屏幕截取授权", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContentView(buildMainContentView())
        loadSavedSettings()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusUi()
    }

    private fun buildMainContentView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#091428"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }

        // Banner Header
        val titleTv = TextView(this).apply {
            text = "⚔️ LOLM 双阶段 AI 战术教练"
            setTextColor(Color.parseColor("#C89B3C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitleTv = TextView(this).apply {
            text = "阶段1：视觉模型提取结构化 JSON ➔ 阶段2：gemini-4-argon 深度阵容博弈"
            setTextColor(Color.parseColor("#0AC8B9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(14))
        }
        container.addView(titleTv)
        container.addView(subtitleTv)

        // Card 1: Launch & Stop Floating Window
        val launchCard = createCardLayout().apply {
            val statusHeader = TextView(this@MainActivity).apply {
                text = "🚀 第一步：启动游戏悬浮窗"
                setTextColor(Color.parseColor("#F0E6D2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(statusHeader)

            val guideTv = TextView(this@MainActivity).apply {
                text = "开启后屏幕边缘会出现「AI战术」悬浮球。点击分析后，先由阶段1视觉模型提取出标准阵容 JSON（支持在悬浮窗内点「JSON」查看或手动改阵容），再自动将 JSON 传给阶段2（gemini-4-argon）做深度克制与出装推演！"
                setTextColor(Color.parseColor("#A09B8C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, dp(12))
            }
            addView(guideTv)

            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnStartService = Button(this@MainActivity).apply {
                text = "开启 AI 战术悬浮窗"
                setTextColor(Color.parseColor("#091428"))
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#C89B3C"))
                }
                setOnClickListener {
                    saveCurrentSettings(silent = true)
                    checkPermissionsAndStartService()
                }
            }

            val btnStopService = Button(this@MainActivity).apply {
                text = "关闭悬浮窗"
                setTextColor(Color.parseColor("#F0E6D2"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#E84057"))
                }
                setOnClickListener {
                    val stopIntent = Intent(this@MainActivity, OverlayCoachService::class.java).apply {
                        action = OverlayCoachService.ACTION_STOP
                    }
                    startService(stopIntent)
                    Toast.makeText(this@MainActivity, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
                    btnStartService.postDelayed({ updateServiceStatusUi() }, 200)
                }
            }

            btnRow.addView(
                btnStartService,
                LinearLayout.LayoutParams(0, dp(44), 1.6f).apply { rightMargin = dp(8) }
            )
            btnRow.addView(
                btnStopService,
                LinearLayout.LayoutParams(0, dp(44), 1f)
            )
            addView(btnRow)
        }
        container.addView(launchCard)

        // Card 2: Two-Stage Model Pipeline Configuration
        val apiCard = createCardLayout().apply {
            addView(createSectionHeader("🧠 第二步：双阶段模型配置 (视觉提JSON + Argon深度推理)"))

            addView(createFieldLabel("快速选择双阶段方案预设："))

            val presetNames = CoachPrefs.PRESETS.map { it.name }
            val spinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    presetNames
                )
                background = createInputBackground()
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            addView(spinner)

            tvPresetHint = TextView(this@MainActivity).apply {
                text = CoachPrefs.PRESETS[0].hint
                setTextColor(Color.parseColor("#0AC8B9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(4), 0, dp(8))
            }
            addView(tvPresetHint)

            var isInitialSpinnerLoad = true
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    (view as? TextView)?.setTextColor(Color.parseColor("#F0E6D2"))
                    val preset = CoachPrefs.PRESETS[position]
                    tvPresetHint.text = "💡 ${preset.hint}"
                    if (!isInitialSpinnerLoad) {
                        etBaseUrl.setText(preset.baseUrl)
                        etVisionModel.setText(preset.defaultVisionModel)
                        etReasoningModel.setText(preset.defaultReasoningModel)
                    }
                    isInitialSpinnerLoad = false
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            addView(createFieldLabel("API Base URL (OpenAI 兼容接口地址)："))
            etBaseUrl = createStyledEditText("https://generativelanguage.googleapis.com/v1beta/openai")
            addView(etBaseUrl)

            addView(createFieldLabel("API Key (已内置你的 Google Gemini Key)："))
            etApiKey = createStyledEditText("AQ.Ab8RN...").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(etApiKey)

            val showKeyCheck = CheckBox(this@MainActivity).apply {
                text = "显示 API Key 明文"
                setTextColor(Color.parseColor("#A09B8C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setOnCheckedChangeListener { _, isChecked ->
                    etApiKey.inputType = if (isChecked) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    etApiKey.setSelection(etApiKey.text.length)
                }
            }
            addView(showKeyCheck)

            addView(createFieldLabel("📸 阶段 1 视觉识别模型 (负责看图输出结构化 JSON)："))
            etVisionModel = createStyledEditText("gemini-2.5-flash")
            addView(etVisionModel)

            addView(createFieldLabel("🧠 阶段 2 深度推理模型 (负责读取 JSON 进行深度战术推演)："))
            etReasoningModel = createStyledEditText("gemini-4-argon")
            addView(etReasoningModel)

            cbStream = CheckBox(this@MainActivity).apply {
                text = "开启阶段 2 SSE 流式实时输出"
                setTextColor(Color.parseColor("#F0E6D2"))
                isChecked = true
            }
            addView(cbStream)

            val apiBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(4))
            }

            val btnSave = Button(this@MainActivity).apply {
                text = "💾 保存双阶段配置"
                setTextColor(Color.parseColor("#091428"))
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#0AC8B9"))
                }
                setOnClickListener {
                    saveCurrentSettings(silent = false)
                }
            }

            val btnTest = Button(this@MainActivity).apply {
                text = "⚡ 测试双模型连通性"
                setTextColor(Color.parseColor("#F0E6D2"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#0AC8B9"))
                }
                setOnClickListener {
                    saveCurrentSettings(silent = true)
                    runApiConnectivityTest()
                }
            }

            apiBtnRow.addView(
                btnSave,
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) }
            )
            apiBtnRow.addView(
                btnTest,
                LinearLayout.LayoutParams(0, dp(42), 1f)
            )
            addView(apiBtnRow)

            tvTestStatus = TextView(this@MainActivity).apply {
                text = ""
                setTextColor(Color.parseColor("#A09B8C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, 0)
                visibility = View.GONE
            }
            addView(tvTestStatus)
        }
        container.addView(apiCard)

        // Card 3: Personalized Role & Tactical System Prompt
        val promptCard = createCardLayout().apply {
            addView(createSectionHeader("🎯 第三步：个人主玩位置与教练 Prompt 定制"))

            addView(createFieldLabel("我的主玩分路 / 个性化关注点（选填，AI 将围绕你的视角分析）："))
            etRoleHint = createStyledEditText("例如：我主玩打野/单人路，段位大师，重点告诉我前期刷野抓人路线和针对装")
            addView(etRoleHint)

            addView(createFieldLabel("阶段 2 教练系统级 Prompt（可自由修改）："))
            etSystemPrompt = createStyledEditText("").apply {
                minLines = 4
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
            }
            addView(etSystemPrompt)

            val btnResetPrompt = Button(this@MainActivity).apply {
                text = "恢复默认王者教练 Prompt"
                setTextColor(Color.parseColor("#C89B3C"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#C89B3C"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(38)
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    etSystemPrompt.setText(CoachPrefs.DEFAULT_SYSTEM_PROMPT)
                    CoachPrefs.setSystemPrompt(this@MainActivity, CoachPrefs.DEFAULT_SYSTEM_PROMPT)
                    Toast.makeText(this@MainActivity, "已恢复默认教练 Prompt", Toast.LENGTH_SHORT).show()
                }
            }
            addView(btnResetPrompt)
        }
        container.addView(promptCard)

        scroll.addView(container)
        return scroll
    }

    private fun loadSavedSettings() {
        etBaseUrl.setText(CoachPrefs.getBaseUrl(this))
        etApiKey.setText(CoachPrefs.getApiKey(this))
        etVisionModel.setText(CoachPrefs.getVisionModel(this))
        etReasoningModel.setText(CoachPrefs.getReasoningModel(this))
        etRoleHint.setText(CoachPrefs.getUserRoleHint(this))
        etSystemPrompt.setText(CoachPrefs.getSystemPrompt(this))
        cbStream.isChecked = CoachPrefs.isStreamEnabled(this)
    }

    private fun saveCurrentSettings(silent: Boolean) {
        CoachPrefs.setBaseUrl(this, etBaseUrl.text.toString())
        CoachPrefs.setApiKey(this, etApiKey.text.toString())
        CoachPrefs.setVisionModel(this, etVisionModel.text.toString())
        CoachPrefs.setReasoningModel(this, etReasoningModel.text.toString())
        CoachPrefs.setUserRoleHint(this, etRoleHint.text.toString())
        CoachPrefs.setSystemPrompt(this, etSystemPrompt.text.toString())
        CoachPrefs.setStreamEnabled(this, cbStream.isChecked)

        if (!silent) {
            Toast.makeText(this, "✅ 双阶段配置已保存，悬浮窗将立即生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runApiConnectivityTest() {
        tvTestStatus.visibility = View.VISIBLE
        tvTestStatus.setTextColor(Color.parseColor("#0AC8B9"))
        tvTestStatus.text = "⏳ 正在测试 阶段1视觉模型 与 阶段2推理模型 连接..."

        VisionLlmClient.testConnection(
            baseUrl = etBaseUrl.text.toString(),
            apiKey = etApiKey.text.toString(),
            visionModel = etVisionModel.text.toString(),
            reasoningModel = etReasoningModel.text.toString(),
            callback = object : VisionLlmClient.StreamCallback {
                override fun onPartialUpdate(fullTextSoFar: String) {}

                override fun onComplete(finalText: String) {
                    tvTestStatus.setTextColor(Color.parseColor("#00C896"))
                    tvTestStatus.text = finalText
                }

                override fun onError(errorMessage: String) {
                    tvTestStatus.setTextColor(Color.parseColor("#E84057"))
                    tvTestStatus.text = "❌ $errorMessage"
                }
            }
        )
    }

    private fun checkPermissionsAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予「显示在其他应用上层」悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        requestMediaProjectionPermission()
    }

    private fun requestMediaProjectionPermission() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun updateServiceStatusUi() {
        if (OverlayCoachService.isRunning) {
            btnStartService.text = "🔄 重新授权/刷新悬浮窗 (运行中)"
        } else {
            btnStartService.text = "开启 AI 战术悬浮窗"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#0A1E36"))
                setStroke(dp(1), Color.parseColor("#33C89B3C"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun createSectionHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#F0E6D2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun createFieldLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#A09B8C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(4))
        }
    }

    private fun createStyledEditText(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F0E6D2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = createInputBackground()
        }
    }

    private fun createInputBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#06101E"))
            setStroke(dp(1), Color.parseColor("#334155"))
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
