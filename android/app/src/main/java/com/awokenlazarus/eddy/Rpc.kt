package com.awokenlazarus.eddy

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RpcError(val code: Int, message: String) : Exception(message)

/** JSON-RPC to the local node, authenticated with the cookie file it writes at startup. */
class Rpc(private val dataDir: File, private val port: Int) {

    fun call(method: String, vararg params: Any?): Any? = callTimeout(120_000, method, *params)

    fun callTimeout(timeoutMs: Int, method: String, vararg params: Any?): Any? {
        val cookie = File(dataDir, ".cookie")
        if (!cookie.exists()) throw IOException("node has not written its cookie yet")
        val auth = "Basic " + Base64.encodeToString(cookie.readText().trim().toByteArray(), Base64.NO_WRAP)
        val body = JSONObject()
            .put("jsonrpc", "1.0").put("id", "eddy").put("method", method)
            .put("params", JSONArray(params.toList()))
        val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        c.connectTimeout = 3_000
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: throw IOException("HTTP $code")
            val j = JSONObject(text)
            val err = j.opt("error")
            if (err is JSONObject) throw RpcError(err.optInt("code"), err.optString("message"))
            return j.opt("result")
        } finally {
            c.disconnect()
        }
    }

    fun obj(method: String, vararg params: Any?): JSONObject = call(method, *params) as JSONObject
    fun arr(method: String, vararg params: Any?): JSONArray = call(method, *params) as JSONArray
}
