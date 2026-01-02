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
        restoreState()
        loadInstances()
        requestPermissions()
    }

    private fun setupListeners() {
        binding.btnStart.setOnClickListener { startVwarp() }
        binding.btnStop.setOnClickListener { stopCurrentInstance() }
        binding.btnStopAll.setOnClickListener { stopVwarp("__ALL__") }
        binding.btnClearLog.setOnClickListener { clearLog() }
        binding.btnSaveLog.setOnClickListener { saveLog() }
        binding.btnNewConfig.setOnClickListener { startActivity(Intent(this, ConfigEditorActivity::class.java)) }
        binding.btnEditConfig.setOnClickListener { editConfig() }
        binding.btnAddInstance.setOnClickListener { addInstance() }

        binding.rgMode.setOnCheckedChangeListener { _, id ->
            val mode = when(id) {
                R.id.rb_simple -> 0
                R.id.rb_cmdline -> 1
                else -> 2
            }
            updateModeUI(mode)
            prefs.edit().putInt("mode", mode).apply()
        }

        binding.spinnerConfig.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = configList.getOrNull(pos)
                if (name != null && name != "(无配置)") {
                    binding.etInstance.setText(name)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.spinnerInstance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = instanceList.getOrNull(pos)
                if (name != null) {
                    binding.etInstance.setText(name)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
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
        when(mode) {
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
        dir.listFiles()?.forEach { configList.add(it.nameWithoutExtension) }
        if (configList.isEmpty()) configList.add("(无配置)")
        binding.spinnerConfig.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, configList)
    }

    private fun loadInstances() {
        val saved = prefs.getStringSet("instances", setOf("default")) ?: setOf("default")
        instanceList.clear()
        instanceList.addAll(saved)
        if (instanceList.isEmpty()) instanceList.add("default")
        binding.spinnerInstance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, instanceList)

        // 选择上次使用的实例
        val lastInstance = prefs.getString("last_instance", "default")
        val idx = instanceList.indexOf(lastInstance)
        if (idx >= 0) binding.spinnerInstance.setSelection(idx)
    }

    private fun saveInstances() {
        prefs.edit().putStringSet("instances", instanceList.toSet()).apply()
    }

    private fun addInstance() {
        val name = binding.etInstance.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入实例名", Toast.LENGTH_SHORT).show()
            return
        }
        if (!instanceList.contains(name)) {
            instanceList.add(name)
            saveInstances()
            (binding.spinnerInstance.adapter as ArrayAdapter<*>).notifyDataSetChanged()
            binding.spinnerInstance.setSelection(instanceList.indexOf(name))
        }
    }

    private fun editConfig() {
        val selected = binding.spinnerConfig.selectedItem?.toString()
        if (selected != null && selected != "(无配置)") {
            val intent = Intent(this, ConfigEditorActivity::class.java)
            intent.putExtra("config_name", selected)
            startActivity(intent)
        } else {
            startActivity(Intent(this, ConfigEditorActivity::class.java))
        }
    }

    private fun getCurrentInstance(): String {
        return binding.etInstance.text.toString().trim().ifEmpty { "default" }
    }

    private fun startVwarp() {
        val instance = getCurrentInstance()

        // 保存当前实例
        if (!instanceList.contains(instance)) {
            instanceList.add(instance)
            saveInstances()
            (binding.spinnerInstance.adapter as ArrayAdapter<*>).notifyDataSetChanged()
        }
        prefs.edit().putString("last_instance", instance).apply()

        val intent = Intent(this, VwarpService::class.java).apply {
            putExtra("action", "start")
            putExtra("instance", instance)
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

        binding.tvStatus.text = "状态: 启动中... [$instance]"
        binding.tvLog.append("--- 启动实例: $instance ---\n")
    }

    private fun stopCurrentInstance() {
        val instance = getCurrentInstance()
        stopVwarp(instance)
    }

    private fun stopVwarp(instance: String) {
        val intent = Intent(this, VwarpService::class.java)
        intent.putExtra("action", "stop")
        intent.putExtra("instance", instance)
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
                Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveLogLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, "vwarp_${System.currentTimeMillis()}.txt").writeText(logBuffer.toString())
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun requestPermissions() {
        // 电池优化
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        }

        // 通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(VwarpService.LOG_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        updateRunningUI()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getInt("mode", 0) == 2) loadConfigs()
        updateRunningUI()
    }
}