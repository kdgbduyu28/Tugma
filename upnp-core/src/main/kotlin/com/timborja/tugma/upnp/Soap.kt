package com.timborja.tugma.upnp

import com.timborja.tugma.core.PlannedRequest
import java.io.IOException

/** A UPnP SOAP fault. [upnpErrorCode] is the device's own code (e.g. 401 = invalid action). */
class SoapFault(
    val httpStatus: Int,
    val upnpErrorCode: Int?,
    val upnpDescription: String?,
    val rawBody: String,
) : IOException(
    buildString {
        append("Speaker rejected the request (HTTP $httpStatus")
        if (upnpErrorCode != null) append(", UPnP error $upnpErrorCode")
        if (!upnpDescription.isNullOrBlank()) append(": $upnpDescription")
        append(")")
    },
)

/** A SOAP action addressed to one service on one device. */
data class SoapCall(
    val baseUrl: String,
    val controlPath: String,
    val serviceType: String,
    val action: String,
    val args: List<Pair<String, String>> = emptyList(),
) {
    val url: String get() = baseUrl.trimEnd('/') + controlPath
    val soapAction: String get() = "$serviceType#$action"

    fun envelope(): String = SoapEnvelope.build(serviceType, action, args)

    fun toHttpRequest(): HttpRequest = HttpRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPACTION" to "\"$soapAction\"",
        ),
        body = envelope(),
    )

    fun toPlannedRequest(label: String): PlannedRequest {
        val http = toHttpRequest()
        return PlannedRequest(http.method, http.url, http.headers, http.body.orEmpty(), label)
    }
}

object SoapEnvelope {
    fun build(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in args) {
            append('<').append(name).append('>').append(Xml.escape(value)).append("</").append(name).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body></s:Envelope>")
    }

    /** Output arguments of `<u:ActionResponse>`, keyed by name. XML-escaped payloads come back unescaped. */
    fun parseResponse(body: String): Map<String, String> {
        val root = Xml.parse(body)
        val bodyEl = root.child("Body") ?: return emptyMap()
        val response = bodyEl.children().firstOrNull() ?: return emptyMap()
        return response.children().associate { it.name to it.textContent }
    }

    fun parseFault(status: Int, body: String): SoapFault {
        val parsed = runCatching { Xml.parse(body) }.getOrNull()
        val code = parsed?.find("errorCode")?.textContent?.trim()?.toIntOrNull()
        val description = parsed?.find("errorDescription")?.textContent?.trim()
            ?: parsed?.find("faultstring")?.textContent?.trim()
        return SoapFault(status, code, description, body)
    }
}

class SoapClient(private val transport: HttpTransport) {
    suspend fun call(call: SoapCall): Map<String, String> {
        val response = transport.execute(call.toHttpRequest())
        if (response.status !in 200..299) throw SoapEnvelope.parseFault(response.status, response.body)
        return SoapEnvelope.parseResponse(response.body)
    }

    /** Sends a request that was planned (and confirmed) earlier, exactly as shown to the user. */
    suspend fun send(planned: PlannedRequest): Map<String, String> {
        val response = transport.execute(HttpRequest(planned.method, planned.url, planned.headers, planned.body))
        if (response.status !in 200..299) throw SoapEnvelope.parseFault(response.status, response.body)
        return SoapEnvelope.parseResponse(response.body)
    }
}
