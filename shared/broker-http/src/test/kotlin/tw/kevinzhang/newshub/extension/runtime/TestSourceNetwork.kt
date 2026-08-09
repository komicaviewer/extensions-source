package tw.kevinzhang.newshub.extension.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tw.kevinzhang.extension_api.SourceNetwork
import tw.kevinzhang.extension_api.SourceNetworkRequest
import tw.kevinzhang.extension_api.SourceNetworkResponse
import tw.kevinzhang.extension_api.AuthState
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime

/** Test-only bridge. Production extensions never receive a transport-capable client. */
fun OkHttpClient.asTestSourceNetwork(): SourceNetwork = object : SourceNetwork {
    override suspend fun execute(request: SourceNetworkRequest): SourceNetworkResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(
            request.headers["Content-Type"]?.toMediaTypeOrNull(),
        )
        builder.method(request.method, body)
        newCall(builder.build()).execute().use { response ->
            return SourceNetworkResponse(
                code = response.code,
                headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                body = response.body?.bytes() ?: ByteArray(0),
            )
        }
    }
}

fun OkHttpClient.asTestSourceRuntime(): SourceRuntime = object : SourceRuntime {
    override val network = asTestSourceNetwork()
    override val authentication = object : AuthenticationSession {
        override val state = MutableStateFlow(AuthState.SignedOut)
        override fun markExpired() = Unit
    }
}
