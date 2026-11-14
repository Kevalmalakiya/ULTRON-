package com.ultron.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UltronApiMessage(
    val role: String,
    val content: String
)

object UltronApiClient {

    fun sendChat(
        baseUrl: String,
        apiKey: String,
        messages: List<UltronApiMessage>
    ): String {
        val cleanBaseUrl = baseUrl.trim().trimEnd('/')
        require(cleanBaseUrl.startsWith("https://", ignoreCase = true)) {
            "The API URL must begin with https://"
        }
        require(apiKey.isNotBlank()) { "API key is missing." }

        val payloadMessages = JSONArray()
        messages.takeLast(12).forEach { message ->
            payloadMessages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }

        val payload = JSONObject()
            .put("messages", payloadMessages)
            .put("max_new_tokens", 256)
            .put("temperature", 0.7)

        val connection = URL(cleanBaseUrl + "/chat").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 240000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val status = connection.responseCode
            val responseStream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = responseStream?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(responseText).optString("detail")
                }.getOrDefault(responseText)
                throw IllegalStateException("Backend HTTP $status: $detail")
            }

            val reply = JSONObject(responseText).optString("reply").trim()
            if (reply.isBlank()) {
                throw IllegalStateException("The backend returned an empty reply.")
            }
            return reply
        } finally {
            connection.disconnect()
        }
    }
}
