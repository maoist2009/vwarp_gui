package org.maoist2009.gui.vwarp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maoist2009.gui.vwarp.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val configList = mutableListOf<String>()
    private val instanceList = mutableListOf<String>()
    private val logBuffer = StringBuilder()

    companion object {
        const val HELP_URL = "https://github.com/bepass-org/warp-plus/blob/main/README.md"
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra("line") ?: return
            val instance = intent.getStringExtra("instance") ?: "sys"
            val logLine = "[$instance] $line"
            logBuffer.append(logLine).append("\n")

            runOnUiThread {
                binding.tvLog.append("$logLine\n")
                binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }

                if (line.contains("connectivity test passed") || line.contains("serving proxy")) {
                    binding.tvStatus.text = "状态: 已就绪 [$instance]"
                    binding.tvStatus.setTextColor(Color.parseColor("#1B5E20"))
                    binding.tvStatus.setBackgroundColor(Color.parseColor("#C8E6C9"))
                }
                updateRunningUI()
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) saveLogLegacy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("vwarp_prefs", MODE_PRIVATE)

        setupListeners()
        loadInstances()
        restoreState()
        requestPermissions()
    }

    private fun setupListeners() {
        binding.btnStart.setOnClickListener { startVwarp() }
        binding.btnStop.setOnClickListener { stopVwarp(getCurrentInstance()) }
        binding.btnStopAll.setOnClickListener { stopVwarp("__ALL__") }
        binding.btnClearLog.setOnClickListener { clearLog() }
        binding.btnSaveLog.setOnClickListener { saveLog() }
        binding.btnNewConfig.setOnClickListener { startActivity(Intent(this, ConfigEditorActivity::class.java)) }
        binding.btnDeleteInstance.setOnClickListener { deleteCurrentInstance() }

        // 帮助按钮 - 打开浏览器
        binding.btnHelp.setOnClickListener { openHelpUrl() }

        binding.btnEditConfig.setOnClickListener {
            val selected = binding.spinnerConfig.selectedItem?.toString()
            if (selected != null && selected != "(无配置)") {
                startActivity(Intent(this, ConfigEditorActivity::class.java).apply {
                    putExtra("config_name", selected)
                })
            } else {
                startActivity(Intent(this, ConfigEditorActivity::class.java))
            }
        }

        binding.rgMode.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rb_simple -> 0
                R.id.rb_cmdline -> 1
                else -> 2
            }
            updateModeUI(mode)
            prefs.edit().putInt("mode", mode).apply()
        }

        // 配置选择时自动填充实例名
        binding.spinnerConfig.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val name = configList.getOrNull(pos)
                if (name != null && name != "(无配置)") {
                    binding.etInstance.setText(name)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 实例下拉选择
        binding.spinnerInstance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val name = instanceList.getOrNull(pos)
                if (name != null) {
                    binding.etInstance.setText(name)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun openHelpUrl() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HELP_URL))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateModeUI(mode: Int) {
        binding.panelSimple.visibility = if (mode == 0) View.VISIBLE else View.GONE
        binding.panelCmdline.visibility = if (mode == 1) View.VISIBLE else View.GONE
        binding.panelConfig.visibility = if (mode == 2) View.VISIBLE else View.GONE
        if (mode == 2) loadConfigs()
    }

    private fun restoreState() {
        val mode = prefs.getInt("mode", 0)
        when (mode) {
            0 -> binding.rbSimple.isChecked = true
            1 -> binding.rbCmdline.isChecked = true
            2 -> binding.rbConfig.isChecked = true
        }
        updateModeUI(mode)
    }

    private fun loadConfigs() {
        val dir = File(filesDir, "configs")
        if (!dir.exists()) dir.mkdirs()
        configList.clear()
        dir.listFiles()?.filter { it.extension == "json" }?.forEach {
            configList.add(it.nameWithoutExtension)
        }
        if (configList.isEmpty()) configList.add("(无配置)")
        binding.spinnerConfig.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, configList)
    }

    private fun loadInstances() {
        val saved = prefs.getStringSet("instances", setOf("default")) ?: setOf("default")
        instanceList.clear()
        instanceList.addAll(saved.sorted())
        if (instanceList.isEmpty()) instanceList.add("default")
        binding.spinnerInstance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, instanceList)

        val lastInstance = prefs.getString("last_instance", "default") ?: "default"
        val idx = instanceList.indexOf(lastInstance)
        if (idx >= 0) binding.spinnerInstance.setSelection(idx)
    }

    private fun saveInstances() {
        prefs.edit().putStringSet("instances", instanceList.toSet()).apply()
    }

    private fun getCurrentInstance(): String {
        return binding.etInstance.text.toString().trim().ifEmpty { "default" }
    }

    private fun deleteCurrentInstance() {
        val current = getCurrentInstance()
        if (current == "default") {
            Toast.makeText(this, "无法删除默认实例", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("删除实例")
            .setMessage("确定删除实例 \"$current\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                instanceList.remove(current)
                saveInstances()
                loadInstances()
                binding.etInstance.setText("default")
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startVwarp() {
        val instanceName = getCurrentInstance()

        // 自动保存新实例
        if (!instanceList.contains(instanceName)) {
            instanceList.add(instanceName)
            saveInstances()
            loadInstances()
        }
        prefs.edit().putString("last_instance", instanceName).apply()

        val intent = Intent(this, VwarpService::class.java).apply {
            putExtra("action", "start")
            putExtra("instance", instanceName)
            putExtra("mode", prefs.getInt("mode", 0))
            putExtra("bind", binding.etBind.text.toString().trim())
            putExtra("endpoint", binding.etEndpoint.text.toString().trim())
            putExtra("proxy", binding.etProxy.text.toString().trim())
            putExtra("cmdline", binding.etCmdline.text.toString().trim())
            putExtra("config_name", binding.spinnerConfig.selectedItem?.toString())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.tvStatus.text = "状态: 启动中... [$instanceName]"
        binding.tvLog.append("--- 启动实例: $instanceName ---\n")
    }

    private fun stopVwarp(instance: String) {
        val intent = Intent(this, VwarpService::class.java).apply {
            putExtra("action", "stop")
            putExtra("instance", instance)
        }
        startService(intent)
        Handler(Looper.getMainLooper()).postDelayed({ updateRunningUI() }, 500)
    }

    private fun updateRunningUI() {
        val running = VwarpService.getRunningInstances()
        binding.tvRunning.text = "运行中: ${if (running.isEmpty()) "无" else running.joinToString(", ")}"

        if (running.isEmpty()) {
            binding.tvStatus.text = "状态: 未运行"
            binding.tvStatus.setBackgroundColor(Color.parseColor("#E0E0E0"))
            binding.tvStatus.setTextColor(Color.BLACK)
        }
    }

    private fun clearLog() {
        logBuffer.clear()
        binding.tvLog.text = ""
    }

    private fun saveLog() {
        if (logBuffer.isEmpty()) {
            Toast.makeText(this, "无日志", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveLogModern()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                saveLogLegacy()
            }
        }
    }

    private fun saveLogModern() {
        lifecycleScope.launch {
            try {
                val fileName = "vwarp_${System.currentTimeMillis()}.txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { it.write(logBuffer.toString().toByteArray()) }
                    }
                    Toast.makeText(this@MainActivity, "已保存到 Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveLogLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, "vwarp_${System.currentTimeMillis()}.txt").writeText(logBuffer.toString())
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestPermissions() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(VwarpService.LOG_ACTION)
        // 修复：Android 13+ 必须指定 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        updateRunningUI()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getInt("mode", 0) == 2) loadConfigs()
        updateRunningUI()
    }
}