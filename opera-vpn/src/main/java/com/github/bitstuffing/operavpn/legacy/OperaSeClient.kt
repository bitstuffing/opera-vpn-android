package com.github.bitstuffing.operavpn.legacy

import org.json.JSONObject
import java.net.URLEncoder
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

class OperaSeClient(
    private val apiUsername: String,
    private val apiSecret: String
) {

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>
        ) {
            cookies.forEach { cookie ->
                store.removeAll {
                    it.name == cookie.name &&
                            it.domain == cookie.domain &&
                            it.path == cookie.path
                }

                if (cookie.expiresAt > System.currentTimeMillis()) {
                    store.add(cookie)
                }
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt <= now }

            return store.filter { it.matches(url) }
        }

        @Synchronized
        fun clear() {
            store.clear()
        }
    }

    private class DigestAuthenticator(
        private val username: String,
        private val secret: String,
        private val random: SecureRandom
    ) : Authenticator {

        private fun hash(algorithm: String, value: String): String? {
            val name = when (algorithm.uppercase(Locale.ROOT).removeSuffix("-SESS")) {
                "MD5" -> "MD5"
                "SHA" -> "SHA-1"
                "SHA-256" -> "SHA-256"
                "SHA-512" -> "SHA-512"
                else -> return null
            }
            return MessageDigest.getInstance(name)
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }

        private fun randomHex(length: Int): String {
            val chars = "0123456789abcdef"
            return buildString(length) { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
        }

        override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
            android.util.Log.d(
                "OperaSeClient",
                "WWW-Authenticate=${response.headers.values("WWW-Authenticate")}"
            )

            if (responseCount(response) >= 2) return null

            val challenge = response.challenges().firstOrNull {
                it.scheme.equals("Digest", ignoreCase = true)
            } ?: return null

            val params = challenge.authParams
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val opaque = params["opaque"]
            val algorithm = params["algorithm"]            // puede ser null => MD5
            val algo = algorithm ?: "MD5"

            val qop = params["qop"]
                ?.split(",")?.map { it.trim() }
                ?.firstOrNull { it.equals("auth", ignoreCase = true) }

            val original = response.request
            val url = original.url
            val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

            val cnonce = randomHex(16)
            val nc = "00000001"

            var ha1 = hash(algo, "$username:$realm:$secret") ?: return null
            if (algo.endsWith("-SESS", ignoreCase = true)) {
                ha1 = hash(algo, "$ha1:$nonce:$cnonce") ?: return null
            }
            val ha2 = hash(algo, "${original.method}:$uri") ?: return null

            val digestResponse = if (qop != null) {
                hash(algo, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            } else {
                hash(algo, "$ha1:$nonce:$ha2")
            } ?: return null

            val authorization = buildString {
                append("""Digest username="$username", realm="$realm", nonce="$nonce", """)
                append("""uri="$uri", response="$digestResponse"""")
                if (opaque != null) append(""", opaque="$opaque"""")
                if (algorithm != null) append(""", algorithm="$algorithm"""")
                if (qop != null) append(""", qop="$qop", nc=$nc, cnonce="$cnonce"""")
            }

            return original.newBuilder().header("Authorization", authorization).build()
        }

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) { count++; prior = prior.priorResponse }
            return count
        }
    }

    companion object {
        private const val CLIENT_VERSION = "Stable 114.0.5282.21"
        const val CLIENT_TYPE = "se0316"
        const val CLIENT_PASSWORD = "SILrMEPBmJuhomxWkfm3JalqHX2Eheg1YhlEZiMh8II"
        private const val DEVICE_NAME = "Opera-Browser-Client"
        private const val OPERATING_SYSTEM = "Windows"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 OPR/114.0.0.0"

        private const val REGISTER_SUBSCRIBER = "https://api2.sec-tunnel.com/v4/register_subscriber"

        private const val SUBSCRIBER_LOGIN = "https://api2.sec-tunnel.com/v4/subscriber_login"

        private const val REGISTER_DEVICE = "https://api2.sec-tunnel.com/v4/register_device"

        private const val DEVICE_GENERATE_PASSWORD = "https://api2.sec-tunnel.com/v4/device_generate_password"

        private const val GEO_LIST = "https://api2.sec-tunnel.com/v4/geo_list"

        private const val DISCOVER = "https://api2.sec-tunnel.com/v4/discover"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        private val random = SecureRandom()
    }

    private var subscriberEmail: String? = null
    private var subscriberPassword: String? = null

    private val deviceId: String = randomString(
        20,
        "0123456789abcdef"
    )

    private var assignedDeviceId: String? = null
    private var assignedDeviceIdHash: String? = null
    private var devicePassword: String? = null

    private val cookieJar = MemoryCookieJar()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(cookieJar)
        .authenticator(DigestAuthenticator(apiUsername, apiSecret, SecureRandom()))
        .build()

    private fun randomString(
        length: Int,
        charset: String =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    ): String {
        return buildString(length) {
            repeat(length) {
                append(charset[random.nextInt(charset.length)])
            }
        }
    }

    private fun sha1HexUpper(value: String): String {
        val digest = MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") {
            "%02X".format(Locale.ROOT, it.toInt() and 0xFF)
        }
    }

    private fun resetCookies() {
        cookieJar.clear()
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "SE-Client-Version" to CLIENT_VERSION,
        "SE-Operating-System" to OPERATING_SYSTEM,
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json"
    )

    private fun encodeForm(
        payload: Map<String, String>
    ): ByteArray {
        val form = payload.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=" +
                    URLEncoder.encode(value, "UTF-8")
        }

        return form.toByteArray(Charsets.UTF_8)
    }

    private fun request(
        endpoint: String,
        payload: Map<String, String>,
        requestHeaders: Map<String, String> = headers()
    ): JSONObject {
        val formBuilder = FormBody.Builder(Charsets.UTF_8)

        payload.forEach { (key, value) ->
            formBuilder.add(key, value)
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(formBuilder.build())

        requestHeaders.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val challenges = response.headers
                    .values("WWW-Authenticate")
                    .map { it.substringBefore(' ') }

                android.util.Log.e(
                    "OperaSeClient",
                    "HTTP ${response.code}, " +
                            "endpoint=${response.request.url.encodedPath}, " +
                            "challenges=$challenges, " +
                            "body=${responseBody.take(1000)}"
                )

                throw IOException(
                    "Opera API HTTP ${response.code}: ${responseBody.take(1000)}"
                )
            }

            try {
                return JSONObject(responseBody)
            } catch (e: Exception) {
                throw IOException(
                    "Respuesta JSON inválida en ${response.request.url.encodedPath}",
                    e
                )
            }
        }
    }

    private fun successful(response: JSONObject): Boolean {
        val codes = response.optJSONObject("return_code")
            ?: return false

        val code = codes.opt("0")

        return code == 0 ||
                code == 0L ||
                code == "0" ||
                code == "OK"
    }

    fun anonRegister(): Boolean {
        resetCookies()

        val localPart = randomString(
            32,
            "abcdefghijklmnopqrstuvwxyz0123456789"
        )

        subscriberEmail = "$localPart@$CLIENT_TYPE.best.vpn"
        subscriberPassword = sha1HexUpper(subscriberEmail!!)

        return register()
    }


    private fun register(): Boolean {
        val email = subscriberEmail ?: return false
        val password = subscriberPassword ?: return false

        return try {
            successful(
                request(
                    endpoint = REGISTER_SUBSCRIBER,
                    payload = mapOf(
                        "email" to email,
                        "password" to password
                    )
                )
            )
        } catch (e: IOException) {
            android.util.Log.e(
                "OperaSeClient",
                "Error en register()",
                e
            )
            false
        } catch (e: Exception) {
            android.util.Log.e(
                "OperaSeClient",
                "Respuesta de registro inválida",
                e
            )
            false
        }
    }
    fun registerDevice(): Boolean {
        return try {
            val response = request(
                REGISTER_DEVICE,
                mapOf(
                    "client_type" to CLIENT_TYPE,
                    "device_hash" to deviceId,
                    "device_name" to DEVICE_NAME
                )
            )

            if (!successful(response)) {
                return false
            }

            val data = response.optJSONObject("data")
                ?: return false

            val deviceIdFromApi = data.optString("device_id")
                .takeIf { it.isNotBlank() }
                ?: return false

            val password = data.optString("device_password")
                .takeIf { it.isNotBlank() }
                ?: return false

            assignedDeviceId = deviceIdFromApi
            devicePassword = password
            assignedDeviceIdHash = sha1HexUpper(deviceIdFromApi)

            true
        } catch (_: Exception) {
            false
        }
    }

    fun login(): Boolean {
        resetCookies()

        val email = subscriberEmail ?: return false
        val password = subscriberPassword ?: return false

        return try {
            successful(
                request(
                    SUBSCRIBER_LOGIN,
                    mapOf(
                        "login" to email,
                        "password" to password,
                        "client_type" to CLIENT_TYPE
                    )
                )
            )
        } catch (_: Exception) {
            false
        }
    }

    fun geoList(): List<JSONObject> {
        val idHash = assignedDeviceIdHash ?: return emptyList()

        return try {
            val response = request(
                GEO_LIST,
                mapOf("device_id" to idHash)
            )

            if (!successful(response)) {
                emptyList()
            } else {
                val geos = response.optJSONObject("data")
                    ?.optJSONArray("geos")
                    ?: return emptyList()

                (0 until geos.length()).mapNotNull {
                    geos.optJSONObject(it)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun discover(countryCode: String): List<JSONObject> {
        val idHash = assignedDeviceIdHash ?: return emptyList()

        return try {
            val response = request(
                DISCOVER,
                mapOf(
                    "serial_no" to idHash,
                    "requested_geo" to "\"$countryCode\",,"
                )
            )

            if (!successful(response)) {
                emptyList()
            } else {
                val ips = response.optJSONObject("data")
                    ?.optJSONArray("ips")
                    ?: return emptyList()

                (0 until ips.length()).mapNotNull {
                    ips.optJSONObject(it)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deviceGeneratePassword(): Boolean {
        val id = assignedDeviceId ?: return false

        return try {
            val response = request(
                DEVICE_GENERATE_PASSWORD,
                mapOf("device_id" to id)
            )

            if (!successful(response)) {
                false
            } else {
                val password = response.optJSONObject("data")
                    ?.optString("device_password")
                    ?.takeIf { it.isNotBlank() }
                    ?: return false

                devicePassword = password
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun initialize(countryCode: String = "EU"): OperaConnection? {
        android.util.Log.d("OperaSeClient", "Fase 1: registro anónimo")
        if (!anonRegister()) {
            android.util.Log.e("OperaSeClient", "Falló registro anónimo")
            return null
        }

        android.util.Log.d("OperaSeClient", "Fase 2: registro de dispositivo")
        if (!registerDevice()) {
            android.util.Log.e("OperaSeClient", "Falló registro de dispositivo")
            return null
        }

        android.util.Log.d("OperaSeClient", "Fase 3: consulta de regiones")
        val geos = geoList()
        android.util.Log.d("OperaSeClient", "Regiones obtenidas: ${geos.size}")

        android.util.Log.d("OperaSeClient", "Fase 4: descubrimiento")
        val proxies = discover(countryCode)
        if (proxies.isEmpty()) {
            android.util.Log.e("OperaSeClient", "Descubrimiento sin resultados")
            return null
        }

        android.util.Log.d("OperaSeClient", "Fase 5: login")
        if (!login()) {
            android.util.Log.e("OperaSeClient", "Falló login")
            return null
        }

        android.util.Log.d("OperaSeClient", "Fase 6: contraseña de dispositivo")
        if (!deviceGeneratePassword()) {
            android.util.Log.e("OperaSeClient", "Falló generación de contraseña")
            return null
        }

        val credentials = getProxyCredentials() ?: run {
            android.util.Log.e("OperaSeClient", "Credenciales incompletas")
            return null
        }

        val firstProxy = proxies.first()
        val host = firstProxy.optString("ip").takeIf { it.isNotBlank() }
            ?: return null
        val port = firstProxy.optJSONArray("ports")?.optInt(0, -1) ?: -1

        if (port !in 1..65535) return null

        return OperaConnection(
            host = host,
            port = port,
            username = credentials.first,
            password = credentials.second,
            countryCode = countryCode,
            sniHostname = "${countryCode.lowercase(Locale.ROOT)}0.sec-tunnel.com"
        )
    }

    fun getProxyCredentials(): Pair<String, String>? {
        val username = assignedDeviceIdHash ?: return null
        val password = devicePassword ?: return null

        return username to password
    }

    data class OperaConnection(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val countryCode: String,
        val sniHostname: String
    )
}

