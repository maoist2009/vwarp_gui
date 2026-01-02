package org.maoist2009.gui.vwarp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.util.concurrent.ConcurrentHashMap

class VwarpService : Service() {
    private val TAG = "VwarpService"
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val LOG_ACTION = "org.maoist2009.gui.vwarp.LOG_UPDATE"
        private const val CHANNEL_ID = "vwarp_channel"
        private const val NOTIFY_ID = 1

        private val processes = ConcurrentHashMap<String, java.lang.Process>()
        private val runningFlags = ConcurrentHashMap<String, Boolean>()

        fun getRunningInstances(): List<String> = processes.keys().toList()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "start"
        val instance = intent?.getStringExtra("instance") ?: "default"

        if (action == "stop") {
            if (instance == "__ALL__") {
                stopAllInstances()
            } else {
                stopInstance(instance)
            }
            if (processes.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification("Vwarp 正在运行")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, notification)
        }

        val mode = intent?.getIntExtra("mode", 0) ?: 0

        // 先停止同名实例
        stopInstance(instance)
        Thread.sleep(200)

        val args = buildArgs(intent, mode, instance)
        if (args != null) {
            startVwarpProcess(instance, args)
        }

        return START_STICKY
    }

    private fun buildArgs(intent: Intent?, mode: Int, instance: String): List<String>? {
        return when (mode) {
            0 -> {
                val bind = intent?.getStringExtra("bind") ?: "127.0.0.1:1077"
                val endpoint = intent?.getStringExtra("endpoint") ?: "162.159.198.2"
                val proxy = intent?.getStringExtra("proxy")
                mutableListOf("--masque", "--noize-preset", "light", "--bind", bind, "-e", endpoint).apply {
                    if (!proxy.isNullOrEmpty()) {
                        add("--proxy")
                        add(proxy)
                    }
                }
            }
            1 -> {
                val cmdline = intent?.getStringExtra("cmdline") ?: ""
                cmdline.split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            2 -> {
                val name = intent?.getStringExtra("config_name")
                if (name == null || name == "(无配置)") {
                    sendLog(instance, "错误: 未选择配置文件")
                    return null
                }
                val file = File(File(filesDir, "configs"), "$name.json")
                if (!file.exists()) {
                    sendLog(instance, "错误: 配置文件不存在")
                    return null
                }
                listOf("--config", file.absolutePath)
            }
            else -> null
        }
    }

    private fun startVwarpProcess(instance: String, args: List<String>) {
        Thread {
            try {
                val vwarpPath = getExecutablePath()
                if (vwarpPath == null) {
                    sendLog(instance, "错误: 找不到 vwarp 二进制文件")
                    sendLog(instance, "请将 libvwarp.so 放入 jniLibs/arm64-v8a/ 目录")
                    return@Thread
                }

                sendLog(instance, "二进制: $vwarpPath")

                val cmd = mutableListOf(vwarpPath).apply { addAll(args) }
                sendLog(instance, "命令: ${cmd.joinToString(" ")}")

                val builder = ProcessBuilder(cmd)
                builder.redirectErrorStream(true)
                builder.directory(filesDir)
                builder.environment()["HOME"] = filesDir.absolutePath

                val process = builder.start()
                processes[instance] = process
                runningFlags[instance] = true

                sendLog(instance, "进程已启动 PID: ${getProcessId(process)}")

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (runningFlags[instance] == true) {
                    line = reader.readLine()
                    if (line == null) break
                    sendLog(instance, line)
                }
                reader.close()

                val exitCode = process.waitFor()
                sendLog(instance, "进程退出 code=$exitCode")

            } catch (e: Exception) {
                sendLog(instance, "异常: ${e.message}")
                Log.e(TAG, "Process error", e)
            } finally {
                processes.remove(instance)
                runningFlags.remove(instance)
                if (processes.isEmpty()) stopSelf()
            }
        }.start()
    }

    private fun getExecutablePath(): String? {
        val nativeFile = File(applicationInfo.nativeLibraryDir, "libvwarp.so")
        if (nativeFile.exists()) {
            nativeFile.setExecutable(true)
            return nativeFile.absolutePath
        }
        return null
    }

    private fun getProcessId(process: java.lang.Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (e: Exception) {
            -1
        }
    }

    private fun stopInstance(instance: String) {
        runningFlags[instance] = false
        processes[instance]?.let { process ->
            try {
                process.destroy()
                Thread.sleep(100)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop error", e)
            }
        }
        processes.remove(instance)
        sendLog(instance, "已停止")
    }

    private fun stopAllInstances() {
        val instances = processes.keys.toList()
        instances.forEach { stopInstance(it) }
        sendLog("system", "所有实例已停止")
    }

    private fun sendLog(instance: String, line: String) {
        sendBroadcast(Intent(LOG_ACTION).apply {
            putExtra("line", line)
            putExtra("instance", instance)
        })
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vwarp:Lock")
        wakeLock?.acquire(2 * 60 * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Vwarp", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Vwarp Proxy")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopAllInstances()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}