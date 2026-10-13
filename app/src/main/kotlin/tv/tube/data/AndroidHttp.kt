package tv.tube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.tube.core.net.Http
import tv.tube.core.net.HttpException
import tv.tube.core.net.HttpResponse
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed [Http] for the core module, plus the shared client the player and image
 * loader use.
 *
 *  - No logging interceptor, ever. URLs carry credentials.
 *  - Redirects are followed only to http(s), and never across scheme from https to http.
 *  - Modest timeouts so a dead provider fails fast on the UI.
 */
object AndroidHttp {
    const val USER_AGENT = "Tube/1.0 (Android TV)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor(SchemeGuard)
            .addInterceptor(UserAgent)
            .build()
    }

    /** Refuses any request whose scheme is not http or https, including after redirects. */
    private object SchemeGuard : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (url.scheme != "http" && url.scheme != "https") throw IOException("Unsupported URL scheme")
            return chain.proceed(chain.request())
        }
    }

    private object UserAgent : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }

    val core: Http = object : Http {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse =
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
                val c = client.newBuilder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                val resp = c.newCall(req).execute()
                val body = resp.body ?: run { resp.close(); throw HttpException(resp.code, "Empty response") }
                HttpResponse(
                    code = resp.code,
                    contentType = resp.header("Content-Type"),
                    contentLength = body.contentLength(),
                    body = body.byteStream(),
                )
            }
    }
}
