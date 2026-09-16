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
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startService(serviceIntent)
            Toast.makeText(this, "截屏服务已就绪，请切换到 X 帖子页面", Toast.LENGTH_LONG).show()
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
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mpManager.createScreenCaptureIntent())
        }

        seekBottomCrop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBottomCropValue.text = "底部避让高度: ${progress} px (推荐 X 设为 260px)"
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
