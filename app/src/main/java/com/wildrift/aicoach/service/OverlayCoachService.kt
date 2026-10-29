package com.wildrift.aicoach.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.wildrift.aicoach.MainActivity
import com.wildrift.aicoach.capture.ScreenCapturer
import com.wildrift.aicoach.config.AnalysisMode
import com.wildrift.aicoach.config.CoachPrefs
import com.wildrift.aicoach.network.VisionLlmClient
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayCoachService : Service() {

    companion object {
        const val ACTION_START = "com.wildrift.aicoach.action.START"
        const val ACTION_STOP = "com.wildrift.aicoach.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "wildrift_ai_coach_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var screenCapturer: ScreenCapturer? = null

    private var floatingBallView: View? = null
    private var floatingPanelView: View? = null
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var tvStatus: TextView? = null
    private var tvResult: TextView? = null
    private var ivPreview: ImageView? = null
    private var etExtraQuestion: EditText? = null
    private var etJsonEditor: EditText? = null
    private var jsonContainerLayout: LinearLayout? = null
    private var scrollResult: ScrollView? = null
    private var panelRootLayout: LinearLayout? = null

    private var isTransparentMode = false
    private var isLargePanelMode = false
    private var isJsonBoxVisible = false
    private var isAnalyzing = false
    private var lastMode: AnalysisMode = AnalysisMode.LOADING_PHASE
    private var lastExtractedJson: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startMediaProjectionForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                screenCapturer?.release()
                val mpManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpManager.getMediaProjection(resultCode, resultData)
                if (projection != null) {
                    screenCapturer = ScreenCapturer(this, projection) {
                        mainHandler.post {
                            Toast.makeText(
                                this,
                                "屏幕捕获已结束，悬浮窗已关闭",
                                Toast.LENGTH_SHORT
                            ).show()
                            stopSelf()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "初始化截屏服务失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (floatingBallView == null && floatingPanelView == null) {
            setupFloatingViews()
        }

        isRunning = true
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureExecutor.execute {
            try {
                screenCapturer?.initOrResizeVirtualDisplay()
            } catch (_: Exception) {
            }
        }
    }

    private fun startMediaProjectionForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "LOLM AI 战术悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持《英雄联盟手游》选人与加载界面 AI 阵容识屏服务运行"
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayCoachService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("LOLM AI 双阶段战术助手运行中")
            .setContentText("阶段1视觉提JSON -> 阶段2 Argon深度阵容博弈")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingOpen)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "退出悬浮窗",
                    pendingStop
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupFloatingViews() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        ballParams = WindowManager.LayoutParams(
            dp(54),
            dp(54),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        panelParams = WindowManager.LayoutParams(
            dp(385),
            dp(310),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(32)
        }

        floatingBallView = createFloatingBallView()
        floatingPanelView = createFloatingPanelView()

        windowManager.addView(floatingBallView, ballParams)
        windowManager.addView(floatingPanelView, panelParams)
        floatingPanelView?.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingBallView(): View {
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D9091428"))
                setStroke(dp(2), Color.parseColor("#C89B3C"))
            }
            elevation = dp(8).toFloat()
        }

        val innerBadge = TextView(this).apply {
            text = "AI\n战术"
            setTextColor(Color.parseColor("#0AC8B9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.9f)
        }

        container.addView(
            innerBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = ballParams.x
                    startY = ballParams.y
                    isDragging = false
                    container.alpha = 1.0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        ballParams.x = startX + dx
                        ballParams.y = startY + dy
                        safeUpdateViewLayout(floatingBallView, ballParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandPanel()
                    } else {
                        val screenW = resources.displayMetrics.widthPixels
                        ballParams.x = if (ballParams.x + dp(27) < screenW / 2) {
                            dp(6)
                        } else {
                            screenW - dp(60)
                        }
                        safeUpdateViewLayout(floatingBallView, ballParams)
                        container.alpha = 0.85f
                    }
                    true
                }

                else -> false
            }
        }

        container.alpha = 0.85f
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingPanelView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = buildPanelBackground(transparent = false)
            elevation = dp(10).toFloat()
        }
        panelRootLayout = root

        // 1. Top Header Bar (Draggable)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = "⚔️ LOLM 双阶段教练 (JSON ➔ Argon)"
            setTextColor(Color.parseColor("#C89B3C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnToggleJson = createMiniTopButton("JSON") { btn ->
            isJsonBoxVisible = !isJsonBoxVisible
            jsonContainerLayout?.visibility = if (isJsonBoxVisible) View.VISIBLE else View.GONE
            btn.setTextColor(
                if (isJsonBoxVisible) Color.parseColor("#0AC8B9") else Color.parseColor("#F0E6D2")
            )
            if (!isJsonBoxVisible) {
                setPanelFocusable(false)
            }
        }

        val btnOpacity = createMiniTopButton("透视") {
            isTransparentMode = !isTransparentMode
            panelRootLayout?.background = buildPanelBackground(isTransparentMode)
        }

        val btnSize = createMiniTopButton("放大") { btn ->
            isLargePanelMode = !isLargePanelMode
            btn.text = if (isLargePanelMode) "缩小" else "放大"
            panelParams.width = if (isLargePanelMode) dp(460) else dp(385)
            panelParams.height = if (isLargePanelMode) dp(370) else dp(310)
            safeUpdateViewLayout(floatingPanelView, panelParams)
        }

        val btnCollapse = createMiniTopButton("收起") {
            setPanelFocusable(false)
            collapseToBall()
        }

        headerRow.addView(tvTitle)
        headerRow.addView(btnToggleJson)
        headerRow.addView(btnOpacity)
        headerRow.addView(btnSize)
        headerRow.addView(btnCollapse)

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        headerRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = panelParams.x
                    startY = panelParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = startX + (event.rawX - downRawX).toInt()
                    panelParams.y = startY + (event.rawY - downRawY).toInt()
                    safeUpdateViewLayout(floatingPanelView, panelParams)
                    true
                }

                else -> false
            }
        }

        root.addView(headerRow)

        // 2. Three Primary Action Buttons Row (BP / Loading / Scoreboard)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }

        val btnBp = createActionTriggerButton("⚡ 选人BP分析", "#0AC8B9") {
            triggerCaptureAndAnalyze(AnalysisMode.BP_PHASE)
        }
        val btnLoading = createActionTriggerButton("⚔️ 加载阵容分析", "#C89B3C") {
            triggerCaptureAndAnalyze(AnalysisMode.LOADING_PHASE)
        }
        val btnScoreboard = createActionTriggerButton("🛡️ 计分板出装", "#38BDF8") {
            triggerCaptureAndAnalyze(AnalysisMode.SCOREBOARD_PHASE)
        }

        actionRow.addView(btnBp, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) })
        actionRow.addView(btnLoading, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) })
        actionRow.addView(btnScoreboard, LinearLayout.LayoutParams(0, dp(32), 1f))

        root.addView(actionRow)

        // 3. Quick One-Tap Tactical Follow-up Chips
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(2), 0, dp(4))
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val quickPrompts = listOf(
            "🎯 推荐Counter位" to (AnalysisMode.BP_PHASE to "重点推荐2个最好打对面的Counter英雄及天赋"),
            "🩸 需要早做重伤/破盾吗?" to (AnalysisMode.LOADING_PHASE to "这把对面回血或护盾多吗？谁必须第一件或第二件出重伤/巨蛇之牙/水银？"),
            "🔥 1级团/首条小龙能接吗?" to (AnalysisMode.LOADING_PHASE to "重点分析1级野区入侵与第一条小龙/先锋团哪边战斗力更强"),
            "⚔️ 团战先杀谁?" to (AnalysisMode.LOADING_PHASE to "这把打团我方第一集火目标是谁？谁负责开团谁负责保排？")
        )

        for ((chipLabel, pair) in quickPrompts) {
            val (mode, question) = pair
            val chip = TextView(this).apply {
                text = chipLabel
                setTextColor(Color.parseColor("#F0E6D2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#260AC8B9"))
                    setStroke(dp(1), Color.parseColor("#660AC8B9"))
                }
                setOnClickListener {
                    etExtraQuestion?.setText(question)
                    triggerCaptureAndAnalyze(mode, overrideQuestion = question)
                }
            }
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(5) }
            )
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // 4. Collapsible Stage-1 Extracted JSON Box (allows inspecting or editing JSON & re-running Argon)
        val jsonBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#CC06101E"))
                setStroke(dp(1), Color.parseColor("#0AC8B9"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        jsonContainerLayout = jsonBox

        val jsonTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvJsonHeader = TextView(this).apply {
            text = "📋 阶段1 识别阵容 JSON（可手动修改英雄后重算）："
            setTextColor(Color.parseColor("#0AC8B9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnReRunJson = TextView(this).apply {
            text = "🧠 用Argon重算JSON"
            setTextColor(Color.parseColor("#091428"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#C89B3C"))
            }
            setOnClickListener {
                reAnalyzeFromCurrentJson()
            }
        }
        jsonTopBar.addView(tvJsonHeader)
        jsonTopBar.addView(btnReRunJson)
        jsonBox.addView(jsonTopBar)

        val jsonEditor = EditText(this).apply {
            hint = "点击分析后，阶段1视觉模型提取的 JSON 会显示在这里..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = null
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    setPanelFocusable(true)
                    v.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
                false
            }
        }
        etJsonEditor = jsonEditor
        jsonBox.addView(jsonEditor)
        root.addView(jsonBox)

        // 5. Status + Thumbnail Preview + Custom Input Row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }

        val previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#475569"))
            }
        }
        ivPreview = previewImg

        val statusTv = TextView(this).apply {
            text = "双阶段就绪：1.视觉提取JSON ➔ 2.Argon深度推演"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dp(6), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvStatus = statusTv

        val inputQuestion = EditText(this).apply {
            hint = "补充如: 我玩打野盲僧"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F0E6D2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setSingleLine(true)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    setPanelFocusable(true)
                    v.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
                false
            }
        }
        etExtraQuestion = inputQuestion

        statusRow.addView(previewImg, LinearLayout.LayoutParams(dp(44), dp(24)))
        statusRow.addView(statusTv)
        statusRow.addView(inputQuestion, LinearLayout.LayoutParams(dp(115), dp(24)))

        root.addView(statusRow)

        // 6. Scrollable AI Output Area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#80050B14"))
                setStroke(dp(1), Color.parseColor("#33C89B3C"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        scrollResult = scroll

        val resultTv = TextView(this).apply {
            text = formatCoachOutput(
                "💡 【双阶段流水线架构已启用】：\n" +
                    "• 【阶段 1（视觉提取 JSON）】：点击按钮截屏后，先调用视觉模型（如 gemini-2.5-flash）将画面中的我方/敌方英雄、分路、召唤师技能、符文与 Ban 位转化为标准 JSON 配置。\n" +
                    "• 【阶段 2（Argon 深度战术推演）】：将阶段 1 生成的 JSON 交给深度推理模型（gemini-4-argon）做阵容克制、强势期曲线与针对出装分析！\n" +
                    "• 点击右上角「JSON」按钮可查看或手动微调识别出的 JSON 并一键重算。"
            )
            setTextColor(Color.parseColor("#F0E6D2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.18f)
        }
        tvResult = resultTv
        scroll.addView(resultTv)
        root.addView(scroll)

        return root
    }

    private fun triggerCaptureAndAnalyze(mode: AnalysisMode, overrideQuestion: String? = null) {
        if (isAnalyzing) {
            Toast.makeText(this, "AI 正在执行双阶段分析，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }

        val capturer = screenCapturer
        if (capturer == null) {
            tvStatus?.text = "❌ 未获得截屏权限，请回主界面重启悬浮窗"
            return
        }

        lastMode = mode
        setPanelFocusable(false)
        isAnalyzing = true
        val visionModel = CoachPrefs.getVisionModel(this)
        val reasoningModel = CoachPrefs.getReasoningModel(this)
        tvStatus?.text = "📸 正在截取无遮挡画面..."

        floatingPanelView?.visibility = View.INVISIBLE
        floatingBallView?.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            captureExecutor.execute {
                val maxDim = CoachPrefs.getImageMaxDim(this)
                val frame = capturer.captureSnapshot(maxDimension = maxDim)

                mainHandler.post {
                    floatingPanelView?.visibility = View.VISIBLE

                    if (frame == null) {
                        isAnalyzing = false
                        tvStatus?.text = "⚠️ 截屏失败，请重试"
                        return@post
                    }

                    ivPreview?.setImageBitmap(frame.previewBitmap)
                    tvStatus?.text = "🔍 阶段1 [$visionModel]：正在识屏生成阵容 JSON..."
                    tvResult?.text = formatCoachOutput(
                        "⏳ 【阶段 1/2】正在调用视觉模型 [$visionModel] 识别画面 (${frame.width}x${frame.height}) 并构建结构化阵容 JSON..."
                    )

                    val extraQ = overrideQuestion ?: (etExtraQuestion?.text?.toString()?.trim() ?: "")
                    val stage1Prompt = CoachPrefs.buildStage1JsonExtractionPrompt(mode)
                    val sysPrompt = CoachPrefs.getSystemPrompt(this)
                    val baseUrl = CoachPrefs.getBaseUrl(this)
                    val apiKey = CoachPrefs.getApiKey(this)
                    val stream = CoachPrefs.isStreamEnabled(this)

                    VisionLlmClient.runTwoStagePipeline(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        visionModel = visionModel,
                        reasoningModel = reasoningModel,
                        stage1Prompt = stage1Prompt,
                        systemPrompt = sysPrompt,
                        stage2PromptBuilder = { extractedJson ->
                            CoachPrefs.buildStage2ReasoningPrompt(this, mode, extractedJson, extraQ)
                        },
                        base64Jpeg = frame.base64Jpeg,
                        useStream = stream,
                        callback = createTwoStageUiCallback(mode, reasoningModel)
                    )
                }
            }
        }, 140L)
    }

    private fun reAnalyzeFromCurrentJson() {
        if (isAnalyzing) {
            Toast.makeText(this, "正在分析中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        val editedJson = etJsonEditor?.text?.toString()?.trim().orEmpty()
        if (editedJson.isEmpty()) {
            Toast.makeText(this, "当前还没有阵容 JSON，请先点击上方按钮识屏或输入 JSON", Toast.LENGTH_SHORT).show()
            return
        }

        setPanelFocusable(false)
        isAnalyzing = true
        lastExtractedJson = editedJson

        val reasoningModel = CoachPrefs.getReasoningModel(this)
        val visionModel = CoachPrefs.getVisionModel(this)
        val extraQ = etExtraQuestion?.text?.toString()?.trim() ?: ""
        val stage2Prompt = CoachPrefs.buildStage2ReasoningPrompt(this, lastMode, editedJson, extraQ)

        tvStatus?.text = "🧠 阶段2 [$reasoningModel]：正在基于修改后的 JSON 深度推演..."
        tvResult?.text = formatCoachOutput(
            "📋 【已加载阵容 JSON】：\n$editedJson\n\n⏳ 【阶段 2/2】正在调用 [$reasoningModel] 进行深度战术分析..."
        )

        VisionLlmClient.runStage2OnlyFromJson(
            baseUrl = CoachPrefs.getBaseUrl(this),
            apiKey = CoachPrefs.getApiKey(this),
            reasoningModel = reasoningModel,
            fallbackModel = visionModel,
            systemPrompt = CoachPrefs.getSystemPrompt(this),
            stage2UserPrompt = stage2Prompt,
            extractedJson = editedJson,
            useStream = CoachPrefs.isStreamEnabled(this),
            callback = createTwoStageUiCallback(lastMode, reasoningModel)
        )
    }

    private fun createTwoStageUiCallback(
        mode: AnalysisMode,
        configuredReasoningModel: String
    ): VisionLlmClient.TwoStageCallback {
        return object : VisionLlmClient.TwoStageCallback {
            override fun onStage1JsonExtracted(extractedJson: String, visionModelUsed: String) {
                lastExtractedJson = extractedJson
                etJsonEditor?.setText(extractedJson)
                tvStatus?.text =
                    "🧠 阶段2 [$configuredReasoningModel]：JSON已提取，正在深度推演..."
                tvResult?.text = formatCoachOutput(
                    "【阶段1 视觉识别 JSON 完成 ($visionModelUsed)】\n$extractedJson\n\n" +
                        "⏳ 【阶段2 深度战术推理 ($configuredReasoningModel)】正在生成博弈报告..."
                )
            }

            override fun onStage2ReasoningPartial(
                extractedJson: String,
                reasoningTextSoFar: String,
                reasoningModelUsed: String
            ) {
                tvStatus?.text = "⚡ 阶段2 [$reasoningModelUsed]：实时输出中..."
                tvResult?.text = formatCoachOutput(
                    "$reasoningTextSoFar\n\n" +
                        "──────────────\n" +
                        "【阶段1 提取的原始阵容 JSON】（点右上角 JSON 可编辑重算）：\n$extractedJson"
                )
            }

            override fun onStage2Complete(
                extractedJson: String,
                finalReasoningText: String,
                reasoningModelUsed: String
            ) {
                isAnalyzing = false
                tvStatus?.text = "✅ ${mode.badge} 完成 | 推理引擎: $reasoningModelUsed"
                tvResult?.text = formatCoachOutput(
                    "$finalReasoningText\n\n" +
                        "──────────────\n" +
                        "【阶段1 视觉提取 JSON】（点右上角 JSON 可编辑重算）：\n$extractedJson"
                )
            }

            override fun onError(stageName: String, errorMessage: String) {
                isAnalyzing = false
                tvStatus?.text = "❌ $stageName 失败"
                tvResult?.text = formatCoachOutput(
                    "【$stageName 出错】\n$errorMessage\n\n" +
                        if (lastExtractedJson.isNotEmpty()) {
                            "【已提取的阶段1 JSON】：\n$lastExtractedJson"
                        } else {
                            "请检查手机网络连接或主界面 API 配置。"
                        }
                )
            }
        }
    }

    private fun formatCoachOutput(raw: String): CharSequence {
        val clean = raw.replace("**", "")
        val ssb = SpannableStringBuilder(clean)

        var startIdx = clean.indexOf('【')
        while (startIdx >= 0) {
            val endIdx = clean.indexOf('】', startIdx)
            if (endIdx > startIdx) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#C89B3C")),
                    startIdx,
                    endIdx + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    startIdx,
                    endIdx + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIdx = clean.indexOf('【', endIdx + 1)
            } else {
                break
            }
        }
        return ssb
    }

    private fun expandPanel() {
        floatingBallView?.visibility = View.GONE
        floatingPanelView?.visibility = View.VISIBLE
    }

    private fun collapseToBall() {
        floatingPanelView?.visibility = View.GONE
        floatingBallView?.visibility = View.VISIBLE
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val currentFlags = panelParams.flags
        panelParams.flags = if (focusable) {
            currentFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            etExtraQuestion?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
            etJsonEditor?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
            currentFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        safeUpdateViewLayout(floatingPanelView, panelParams)
    }

    private fun buildPanelBackground(transparent: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(
                if (transparent) Color.parseColor("#99091428")
                else Color.parseColor("#EC091428")
            )
            setStroke(dp(1.5f), Color.parseColor("#C89B3C"))
        }
    }

    private fun createMiniTopButton(label: String, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#F0E6D2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#64748B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(4) }
            setOnClickListener { onClick(this) }
        }
    }

    private fun createActionTriggerButton(
        label: String,
        accentHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#091428"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor(accentHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun safeUpdateViewLayout(view: View?, params: WindowManager.LayoutParams) {
        if (view != null && view.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()

    override fun onDestroy() {
        isRunning = false
        try {
            floatingBallView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            floatingPanelView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        floatingBallView = null
        floatingPanelView = null
        screenCapturer?.release()
        screenCapturer = null
        super.onDestroy()
    }
}
