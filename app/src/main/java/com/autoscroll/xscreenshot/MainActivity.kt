package com.autoscroll.xscreenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MENU_ID_MANUAL_STITCH = 1001
    }

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
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "截屏服务已就绪，请切换到 X 帖子页面", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updatePermissionStatus()
    }

    /**
     * 顶部菜单栏入口：点击可直接进入【批量导入连续截屏拼长图】
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ID_MANUAL_STITCH, 0, "手动拼长图")
            .setIcon(android.R.drawable.ic_menu_gallery)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_ID_MANUAL_STITCH) {
            openManualStitchActivity()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openManualStitchActivity() {
        startActivity(Intent(this, ManualStitchActivity::class.java))
    }

    private fun initViews() {
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnStartService = findViewById(R.id.btnStartService)
        seekBottomCrop = findViewById(R.id.seekBottomCrop)
        tvBottomCropValue = findViewById(R.id.tvBottomCropValue)

        // 若布局中含有手动拼图入口按钮，则绑定跳转事件
        val manualBtnId = resources.getIdentifier("btnManualStitch", "id", packageName)
        if (manualBtnId != 0) {
            findViewById<View>(manualBtnId)?.setOnClickListener {
                openManualStitchActivity()
            }
        }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限，再启动截屏悬浮控制球！", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            try {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(mpManager.createScreenCaptureIntent())
            } catch (e: Exception) {
                Toast.makeText(this, "无法发起截屏授权: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        val prefs = getSharedPreferences("x_screenshot_prefs", Context.MODE_PRIVATE)
        val savedCrop = prefs.getInt("bottom_crop_px", 260)
        seekBottomCrop.progress = savedCrop
        tvBottomCropValue.text = "底部避让高度: ${savedCrop} px (推荐 X 设为 260px)"

        seekBottomCrop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBottomCropValue.text = "底部避让高度: ${progress} px (推荐 X 设为 260px)"
                prefs.edit().putInt("bottom_crop_px", progress).apply()
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
