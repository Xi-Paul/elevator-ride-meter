package kr.xi.ridemeter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 를 확인해 새 APK가 올라왔는지 알린다.
 * 서버 없이 [[kid-schedule]] 과 같은 방식 — GitHub REST API 를 그대로 백엔드로 쓴다.
 * 설치 자체는 사용자가 브라우저에서 내려받아 진행한다(무인 설치는 시스템 권한이 필요).
 */
object Updater {

    private const val PREF = "updater"
    private const val KEY_SKIP = "skipTag"
    private const val KEY_LAST = "lastCheck"

    /** 하루 한 번만 조용히 확인. manual=true 면 항상 확인하고 결과를 알린다. */
    fun check(act: Activity, manual: Boolean, notify: (String) -> Unit) {
        val sp = act.getSharedPreferences(PREF, 0)
        val now = System.currentTimeMillis()
        if (!manual && now - sp.getLong(KEY_LAST, 0) < 24 * 3600_000L) return
        sp.edit().putLong(KEY_LAST, now).apply()

        val repo = act.getString(R.string.update_repo)
        val cur = try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "dev"
        } catch (e: Exception) { "dev" }

        Thread {
            var tag: String? = null
            var apk: String? = null
            var page: String? = null
            var note: String? = null
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection()
                        as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                if (c.responseCode == 200) {
                    val o = JSONObject(c.inputStream.bufferedReader().readText())
                    tag = o.optString("tag_name", null)
                    page = o.optString("html_url", null)
                    note = o.optString("name", null)
                    val assets = o.optJSONArray("assets")
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apk = a.optString("browser_download_url"); break
                        }
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                if (manual) Handler(Looper.getMainLooper()).post { notify("업데이트 확인 실패: ${e.message}") }
                return@Thread
            }

            Handler(Looper.getMainLooper()).post {
                if (tag.isNullOrEmpty()) {
                    if (manual) notify("릴리스를 찾지 못했습니다 ($repo)")
                    return@post
                }
                if (tag == cur) {
                    if (manual) notify("최신 버전입니다 ($cur)")
                    return@post
                }
                if (!manual && tag == sp.getString(KEY_SKIP, null)) return@post
                val url = apk ?: page ?: return@post
                AlertDialog.Builder(act)
                    .setTitle("새 버전 $tag")
                    .setMessage(
                        "현재 $cur → 새 버전 $tag" + (if (!note.isNullOrEmpty()) "\n$note" else "") +
                            "\n\n내려받아 설치하시겠습니까? 기존 앱 위에 덮어 설치되며 측정 데이터는 유지됩니다."
                    )
                    .setPositiveButton("내려받기") { _, _ ->
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNeutralButton("이 버전 건너뛰기") { _, _ ->
                        sp.edit().putString(KEY_SKIP, tag).apply()
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        }.start()
    }
}
