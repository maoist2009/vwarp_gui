package org.maoist2009.gui.vwarp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.maoist2009.gui.vwarp.databinding.ActivityConfigEditorBinding
import java.io.File

class ConfigEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigEditorBinding
    private var originalName: String? = null

    private val defaultTemplate = """{
  "endpoint": "162.159.198.2:443",
  "bind": "127.0.0.1:1077",
  "masque": true,
  "noize_preset": "light"
}"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        originalName = intent.getStringExtra("config_name")

        if (originalName != null) {
            binding.etConfigName.setText(originalName)
            loadConfig(originalName!!)
            title = "编辑配置"
        } else {
            binding.etConfigContent.setText(defaultTemplate)
            title = "新建配置"
        }

        binding.btnLoadTemplate.setOnClickListener {
            binding.etConfigContent.setText(defaultTemplate)
        }

        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnDelete.setOnClickListener { deleteConfig() }
    }

    private fun getConfigDir(): File {
        val dir = File(filesDir, "configs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadConfig(name: String) {
        val file = File(getConfigDir(), "$name.json")
        if (file.exists()) {
            binding.etConfigContent.setText(file.readText())
        }
    }

    private fun saveConfig() {
        val name = binding.etConfigName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入名称", Toast.LENGTH_SHORT).show()
            return
        }

        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val content = binding.etConfigContent.text.toString()

        if (originalName != null && originalName != safeName) {
            File(getConfigDir(), "$originalName.json").delete()
        }

        File(getConfigDir(), "$safeName.json").writeText(content)
        Toast.makeText(this, "已保存: $safeName", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun deleteConfig() {
        val name = binding.etConfigName.text.toString().trim()
        if (name.isEmpty()) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除 $name?")
            .setPositiveButton("删除") { _, _ ->
                File(getConfigDir(), "$name.json").delete()
                originalName?.let { File(getConfigDir(), "$it.json").delete() }
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}