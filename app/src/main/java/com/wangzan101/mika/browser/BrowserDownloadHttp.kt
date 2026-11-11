package com.wangzan101.mika.browser

import okhttp3.OkHttpClient

/** Redirects can keep downloading, but only the original origin may receive WebView cookies. */
internal fun browserDownloadClient(http: OkHttpClient, cookies: (String) -> String?): OkHttpClient =
    http.newBuilder().addNetworkInterceptor { chain ->
        val request = chain.request()
        val origin = chain.call().request().url
        val url = request.url
        val outgoing = request.newBuilder().removeHeader("Cookie")
        if (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
            cookies(url.toString())?.takeIf { it.isNotBlank() }?.let { outgoing.header("Cookie", it) }
        }
        chain.proceed(outgoing.build())
    }.build()
