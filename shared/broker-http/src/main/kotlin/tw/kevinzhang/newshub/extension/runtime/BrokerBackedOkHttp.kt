package tw.kevinzhang.newshub.extension.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tw.kevinzhang.extension_api.SourceNetworkRequest
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.NetworkOperations

/**
 * Keeps existing request builders and parsers while removing their ambient network authority.
 * The terminal interceptor never calls the OkHttp transport; every request crosses the
 * source-scoped Host broker supplied to the isolated Service session.
 */
fun SourceRuntime.brokerBackedHttpClient(operation: String = NetworkOperations.SOURCE_READ): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val response = runBlocking {
                network.execute(
                    SourceNetworkRequest(
                        operation = operation,
                        method = request.method,
                        url = request.url.toString(),
                        headers = request.headers.toMultimap().mapValues { (_, values) ->
                            values.joinToString(",")
                        },
                        body = request.body?.let { body ->
                            okio.Buffer().use { buffer ->
                                body.writeTo(buffer)
                                buffer.readByteArray()
                            }
                        },
                    ),
                )
            }
            val headers = okhttp3.Headers.Builder().apply {
                response.headers.forEach { (name, value) -> add(name, value) }
            }.build()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.code)
                .message("Brokered response")
                .headers(headers)
                .body(
                    response.body.toResponseBody(
                        headers["Content-Type"]?.toMediaTypeOrNull(),
                    ),
                )
                .build()
        }
        .build()
