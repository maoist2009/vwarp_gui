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
        const val HELP_URL = "https://github.com/maoist2009/Vwarp_GUI"
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
                    binding.tvStatus.text = "就绪 [$instance]"
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
        prefs = getSharedPreferences("vwarp", MODE_PRIVATE)

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
        binding.btnHelp.setOnClickListener { openUrl(HELP_URL) }

        binding.btnEditConfig.setOnClickListener {
            val s = binding.spinnerConfig.selectedItem?.toString()
            if (s != null && s != "(无)") {
                startActivity(Intent(this, ConfigEditorActivity::class.java).putExtra("config_name", s))
            }
        }

        binding.rgMode.setOnCheckedChangeListener { _, id ->
            val mode = when (id) { R.id.rb_simple -> 0; R.id.rb_cmdline -> 1; else -> 2 }
            updateModeUI(mode)
            prefs.edit().putInt("mode", mode).apply()
        }

        binding.spinnerConfig.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                configList.getOrNull(pos)?.takeIf { it != "(无)" }?.let { binding.etInstance.setText(it) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.spinnerInstance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                instanceList.getOrNull(pos)?.let { binding.etInstance.setText(it) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun updateModeUI(mode: Int) {
        binding.panelSimple.visibility = if (mode == 0) View.VISIBLE else View.GONE
        binding.panelCmdline.visibility = if (mode == 1) View.VISIBLE else View.GONE
        binding.panelConfig.visibility = if (mode == 2) View.VISIBLE else View.GONE
        if (mode == 2) loadConfigs()
    }

    private fun restoreState() {
        val mode = prefs.getInt("mode", 0)
        when (mode) { 0 -> binding.rbSimple; 1 -> binding.rbCmdline; else -> binding.rbConfig }.isChecked = true
        updateModeUI(mode)
    }

    private fun loadConfigs() {
        val dir = File(filesDir, "configs").also { if (!it.exists()) it.mkdirs() }
        configList.clear()
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { configList.add(it.nameWithoutExtension) }
        if (configList.isEmpty()) configList.add("(无)")
        binding.spinnerConfig.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, configList)
    }

    private fun loadInstances() {
        instanceList.clear()
        instanceList.addAll((prefs.getStringSet("instances", setOf("default")) ?: setOf("default")).sorted())
        if (instanceList.isEmpty()) instanceList.add("default")
        binding.spinnerInstance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, instanceList)
        val last = prefs.getString("last", "default") ?: "default"
        instanceList.indexOf(last).takeIf { it >= 0 }?.let { binding.spinnerInstance.setSelection(it) }
    }

    private fun getCurrentInstance() = binding.etInstance.text.toString().trim().ifEmpty { "default" }

    private fun deleteCurrentInstance() {
        val c = getCurrentInstance()
        if (c == "default") return
        AlertDialog.Builder(this).setMessage("删除 $c?")
            .setPositiveButton("确定") { _, _ ->
                instanceList.remove(c)
                prefs.edit().putStringSet("instances", instanceList.toSet()).apply()
                loadInstances()
                binding.etInstance.setText("default")
            }.setNegativeButton("取消", null).show()
    }

    private fun startVwarp() {
        val name = getCurrentInstance()
        if (!instanceList.contains(name)) {
            instanceList.add(name)
            prefs.edit().putStringSet("instances", instanceList.toSet()).apply()
            loadInstances()
        }
        prefs.edit().putString("last", name).apply()

        Intent(this, VwarpService::class.java).apply {
            putExtra("action", "start")
            putExtra("instance", name)
            putExtra("mode", prefs.getInt("mode", 0))
            putExtra("bind", binding.etBind.text.toString().trim())
            putExtra("endpoint", binding.etEndpoint.text.toString().trim())
            putExtra("proxy", binding.etProxy.text.toString().trim())
            putExtra("cmdline", binding.etCmdline.text.toString().trim())
            putExtra("config_name", binding.spinnerConfig.selectedItem?.toString())
        }.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
        }

        binding.tvStatus.text = "启动中..."
        binding.tvLog.append("--- 启动: $name ---\n")
    }

    private fun stopVwarp(instance: String) {
        startService(Intent(this, VwarpService::class.java).putExtra("action", "stop").putExtra("instance", instance))
        Handler(Looper.getMainLooper()).postDelayed({ updateRunningUI() }, 500)
    }

    private fun updateRunningUI() {
        val r = VwarpService.getRunningInstances()
        binding.tvRunning.text = "运行: ${if (r.isEmpty()) "无" else r.joinToString(",")}"
        if (r.isEmpty()) {
            binding.tvStatus.text = "未运行"
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

        // Android Q (API 29) 及以上使用 MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveLogMediaStore()
        } else {
            // Android 6-9 使用传统方式，需要存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                saveLogLegacy()
            } else {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun saveLogMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        lifecycleScope.launch {
            try {
                val fileName = "vwarp_${System.currentTimeMillis()}.txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(logBuffer.toString().toByteArray())
                        }
                    }
                    Toast.makeText(this@MainActivity, "已保存到 Downloads", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveLogLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "vwarp_${System.currentTimeMillis()}.txt")
                file.writeText(logBuffer.toString())
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已保存: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestPermissions() {
        // 电池优化 (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }

        // 通知权限 (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerLogReceiver()
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

    private fun registerLogReceiver() {
        val filter = IntentFilter(VwarpService.LOG_ACTION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 必须指定 RECEIVER_NOT_EXPORTED
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8-12
            registerReceiver(logReceiver, filter)
        } else {
            // Android 6-7
            registerReceiver(logReceiver, filter)
        }
    }
}