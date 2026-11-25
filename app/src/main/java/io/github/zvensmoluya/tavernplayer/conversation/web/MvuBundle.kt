package io.github.zvensmoluya.tavernplayer.conversation.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A request for the public MVU bundle, regardless of whether Player can host it. */
internal class MvuBundle private constructor(val incompatibility: String?) {
    companion object {
        private val mirrors = setOf("cdn.jsdelivr.net", "testingcf.jsdelivr.net", "fastly.jsdelivr.net")
        private val channels = setOf("", "master", "beta")

        fun from(address: String): MvuBundle? {
            val url = address.toHttpUrlOrNull() ?: return null
            val path = url.pathSegments.takeLast(5)
            if (path.size != 5 || path[0] != "gh" || path[1] != "MagicalAstrogy" ||
                path[3] != "artifact" || path[4] != "bundle.js") return null
            val repo = path[2]
            if (repo.substringBefore('@') != "MagVarUpdate") return null
            val ref = repo.substringAfter('@', "")
            val reason = when {
                !url.isHttps || url.host !in mirrors || url.port != 443 || url.pathSegments.size != 5 ||
                    url.username.isNotEmpty() || url.password.isNotEmpty() ->
                    "此 MVU 加载地址不属于支持的 HTTPS 镜像"
                ref !in channels || repo.endsWith('@') -> "播放器尚未支持此 MVU 版本（@${ref.take(60)}）"
                else -> null
            }
            return MvuBundle(reason)
        }
    }
}
