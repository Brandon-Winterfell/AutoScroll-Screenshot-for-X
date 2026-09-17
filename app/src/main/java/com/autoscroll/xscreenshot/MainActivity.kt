package com.autoscroll.xscreenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnStartService: Button
    private lateinit var seekBottomCrop: SeekBar
    private lateinit var tvBottomCropValue: TextView

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 1. 启动录屏前台服务
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // 2. 双重保障：直接由 Activity 唤起悬浮胶囊控制球
            FloatingOverlayService.show(this)

            Toast.makeText(this, "服务已启动，请切换到 X 应用！", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updatePermissionStatus()
    }

    private fun initViews() {
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnStartService = findViewById(R.id.btnStartService)
        seekBottomCrop = findViewById(R.id.seekBottomCrop)
        tvBottomCropValue = findViewById(R.id.tvBottomCropValue)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        btnStartService.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                // 如果截屏服务原本就在运行，直接重显悬浮窗，不需要重复请求系统录屏权限
                FloatingOverlayService.show(this)
                Toast.makeText(this, "悬浮控制球已恢复显示！", Toast.LENGTH_SHORT).show()
            } else {
                // 首次启动，调起系统录屏授权
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        }

        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val savedCrop = prefs.getInt("bottom_crop_px", 280)
        seekBottomCrop.progress = savedCrop
        tvBottomCropValue.text = "底部避让高度: ${savedCrop} px (推荐 X 设为 260~320px)"

        seekBottomCrop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actualValue = progress.coerceAtLeast(100)
                tvBottomCropValue.text = "底部避让高度: ${actualValue} px (推荐 X 设为 260~320px)"
                if (fromUser) {
                    prefs.edit().putInt("bottom_crop_px", actualValue).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        btnOverlay.isEnabled = !hasOverlay
        btnOverlay.text = if (hasOverlay) "已授予悬浮窗权限 ✓" else "授予悬浮窗权限"

        val hasAccessibility = AutoScrollAccessibilityService.instance != null
        btnAccessibility.isEnabled = !hasAccessibility
        btnAccessibility.text = if (hasAccessibility) "已开启无障碍服务 ✓" else "开启无障碍滚动服务"
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }
}
