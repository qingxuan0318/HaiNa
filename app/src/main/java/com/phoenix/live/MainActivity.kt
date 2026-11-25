package com.phoenix.live

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

data class Channel(val name: String, val liveId: String, val fallbackUrl: String)

class MainActivity : Activity() {

    companion object {
        private const val TAG = "PhoenixTV"
        private const val API_URL =
            "https://api.fengshows.cn/hub/live/auth-url?live_id=%s&live_qa=HD"
    }

    // 官方 ifeng 直连源，作为 API 被 WAF 拦截（HTTP 418）时的兜底
    private val channels = listOf(
        Channel("凤凰中文台", "f7f48462-9b13-485b-8101-7b54716411ec",
            "https://playtv-live.ifeng.com/live/06OLEGEGM4G.m3u8"),
        Channel("凤凰资讯台", "7c96b084-60e1-40a9-89c5-682b994fb680",
            "https://playtv-live.ifeng.com/live/06OLEEWQKN4.m3u8"),
        Channel("凤凰香港台", "15e02d92-1698-416c-af2f-3e9a872b4d78",
            "https://playtv-live.ifeng.com/live/06OLEGEGM4G.m3u8")
    )

    private var currentIndex = 0
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var statusText: TextView
    private val tabs = arrayOfNulls<TextView>(3)

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var retryRunnable: Runnable? = null
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoLayout = findViewById(R.id.video_view)
        statusText = findViewById(R.id.status)
        tabs[0] = findViewById(R.id.tab0)
        tabs[1] = findViewById(R.id.tab1)
        tabs[2] = findViewById(R.id.tab2)

        for (i in 0 until channels.size) {
            tabs[i]?.setOnClickListener { switchTo(i) }
        }

        val options = ArrayList<String>().apply {
            add("--no-video-title-show")
            add("--no-osd")
            add("--network-caching=2500")
            add("--file-caching=1500")
        }
        libVLC = LibVLC(this, options)
        player = MediaPlayer(libVLC)
        player.attachViews(videoLayout, null, false, false)

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    setStatus("直播中 · ${channels[currentIndex].name}    （← → 换台，1/2/3 直达）")
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering == 100f && player.isPlaying) {
                        setStatus("直播中 · ${channels[currentIndex].name}    （← → 换台，1/2/3 直达）")
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC playback error on ${channels[currentIndex].name}")
                    setStatus("连接中断，3 秒后自动重连…")
                    scheduleRetry()
                }
                MediaPlayer.Event.EndReached -> {
                    setStatus("直播结束，正在重连…")
                    scheduleRetry()
                }
            }
        }

        updateTabs()
        playCurrent()
    }

    private fun setStatus(text: String) {
        if (!destroyed) statusText.text = text
    }

    private fun updateTabs() {
        for (i in 0 until channels.size) {
            tabs[i]?.let { tab ->
                val active = i == currentIndex
                tab.setBackgroundColor(
                    if (active) Color.parseColor("#B7974F") else Color.parseColor("#182033")
                )
                tab.setTextColor(if (active) Color.parseColor("#101010") else Color.parseColor("#C9D3E4"))
            }
        }
    }

    private fun scheduleRetry() {
        if (destroyed) return
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { if (!destroyed) playCurrent() }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, 3000)
    }

    private fun switchTo(index: Int) {
        val next = when {
            index < 0 -> channels.size - 1
            index >= channels.size -> 0
            else -> index
        }
        if (next == currentIndex && player.isPlaying) return
        currentIndex = next
        updateTabs()
        playCurrent()
    }

    private fun playCurrent() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        loadJob?.cancel()
        setStatus("正在获取 ${channels[currentIndex].name} 直播地址…")
        val indexAtRequest = currentIndex

        loadJob = scope.launch {
            val channel = channels[indexAtRequest]
            // 先试官方 API，失败则回退到 ifeng 直连源
            val url = withContext(Dispatchers.IO) {
                fetchLiveUrl(channel.liveId) ?: channel.fallbackUrl
            }
            if (destroyed || indexAtRequest != currentIndex) return@launch
            if (url.isNullOrEmpty()) {
                setStatus("获取地址失败，请检查网络后按 OK 重试")
                return@launch
            }
            setStatus("正在连接 ${channels[indexAtRequest].name}…")
            try {
                player.stop()
                val media = Media(libVLC, url)
                player.media = media
                media.release()
                player.play()
            } catch (e: Exception) {
                Log.e(TAG, "start playback failed", e)
                setStatus("播放启动失败，3 秒后重试…")
                scheduleRetry()
            }
        }
    }

    private fun fetchLiveUrl(liveId: String): String? {
        return try {
            val request = Request.Builder()
                .url(String.format(API_URL, liveId))
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optString("status") != "0") return null
                val url = json.getJSONObject("data").optString("live_url")
                url.ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch live url failed", e)
            null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { switchTo(currentIndex - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { switchTo(currentIndex + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { switchTo(currentIndex - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { switchTo(currentIndex + 1); return true }
            KeyEvent.KEYCODE_1 -> { switchTo(0); return true }
            KeyEvent.KEYCODE_2 -> { switchTo(1); return true }
            KeyEvent.KEYCODE_3 -> { switchTo(2); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playCurrent(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        destroyed = true
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        loadJob?.cancel()
        scope.cancel()
        try {
            player.stop()
            player.release()
            libVLC.release()
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
        super.onDestroy()
    }
}
