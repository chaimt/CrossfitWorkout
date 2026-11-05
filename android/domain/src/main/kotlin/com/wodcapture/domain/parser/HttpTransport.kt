package com.wodcapture.domain.parser

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal HTTP request/response, so the parser is testable with a fake (REQ-TS01). */
data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Blocking HTTP transport. Throws [IOException] on network failure or timeout. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/** Production transport on OkHttp. Never logs headers or bodies (the API key is in them, REQ-NF07). */
class OkHttpTransport(timeoutSec: Long = 120) : HttpTransport {
    private val client = OkHttpClient.Builder()
        .callTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = request.body?.toRequestBody("application/json".toMediaType())
        builder.method(request.method, body)
        client.newCall(builder.build()).execute().use { resp ->
            val headers = resp.headers.names().associateWith { resp.header(it).orEmpty() }
            return HttpResponse(resp.code, headers, resp.body?.string().orEmpty())
        }
    }
}
