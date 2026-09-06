package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import okhttp3.OkHttpClient

/** Identify the actual API client consistently for discovery, probes, compilation, and chat. */
object PlayerModelHttpClient {
    const val USER_AGENT = "TavernPlayer"

    fun create(): OkHttpClient = GatewayTransport.defaultClient().newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}
