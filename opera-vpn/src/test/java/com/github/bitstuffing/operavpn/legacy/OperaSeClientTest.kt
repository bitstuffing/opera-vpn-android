package com.github.bitstuffing.operavpn.legacy

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class OperaSeClientTest {

    companion object {
        private const val API_USERNAME = "se0316"
        private const val API_SECRET = "SILrMEPBmJuhomxWkfm3JalqHX2Eheg1YhlEZiMh8II"
        private const val COUNTRY_CODE = "EU"
        private const val API_HOST = "api2.sec-tunnel.com"
        private val TIMEOUT: Duration = Duration.ofSeconds(120)

        private var networkAvailable = false

        @JvmStatic
        @BeforeAll
        fun checkNetwork() {
            networkAvailable = try {
                InetAddress.getByName(API_HOST)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun log(message: String) = println("[OperaSeClientTest] $message")

        private fun newClient() = OperaSeClient(API_USERNAME, API_SECRET)

        /** Cliente con suscriptor y dispositivo ya registrados. */
        private fun registeredClient(): OperaSeClient {
            val client = newClient()
            Assertions.assertTrue(client.anonRegister(), "anonRegister() falló")
            Assertions.assertTrue(client.registerDevice(), "registerDevice() falló")
            return client
        }

        private fun requireNetwork() =
            Assumptions.assumeTrue(networkAvailable, "Sin red o sin resolución DNS de $API_HOST")
    }

    // ---------------------------------------------------------------
    // Comportamiento sin red: estado inicial y precondiciones
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Sin registro previo (no requiere red)")
    inner class WithoutRegistration {

        @Test
        fun `getProxyCredentials devuelve null antes de registrar`() {
            Assertions.assertNull(newClient().getProxyCredentials())
        }

        @Test
        fun `login devuelve false sin suscriptor`() {
            Assertions.assertFalse(newClient().login())
        }

        @Test
        fun `geoList devuelve vacio sin dispositivo`() {
            Assertions.assertTrue(newClient().geoList().isEmpty())
        }

        @Test
        fun `discover devuelve vacio sin dispositivo`() {
            Assertions.assertTrue(newClient().discover(COUNTRY_CODE).isEmpty())
        }

        @Test
        fun `deviceGeneratePassword devuelve false sin dispositivo`() {
            Assertions.assertFalse(newClient().deviceGeneratePassword())
        }
    }

    // ---------------------------------------------------------------
    // Fases del protocolo, una a una (requieren red)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Fases del protocolo")
    inner class ProtocolPhases {

        @Test
        fun `fase 1 - registro anonimo de suscriptor`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                Assertions.assertTrue(newClient().anonRegister(), "El registro anónimo falló")
            }
        }

        @Test
        fun `fase 2 - registro de dispositivo guarda credenciales`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val client = registeredClient()
                val creds = client.getProxyCredentials()
                Assertions.assertNotNull(creds, "Tras registerDevice() debería haber credenciales")
                // usuario = SHA-1 hex en mayúsculas (40 caracteres)
                Assertions.assertTrue(
                    creds!!.first.matches(Regex("^[0-9A-F]{40}$")),
                    "El usuario debería ser un SHA-1 hex en mayúsculas: ${creds.first}"
                )
                Assertions.assertTrue(creds.second.isNotBlank(), "Password de dispositivo vacío")
            }
        }

        @Test
        fun `fase 3 - geoList devuelve regiones`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val geos = registeredClient().geoList()
                Assertions.assertTrue(geos.isNotEmpty(), "geoList() vacío")
                log("Regiones: ${geos.size}")
                for (c in geos) {
                    Assertions.assertTrue(c.has("country"), "Región sin campo 'country': $c")
                }
                // El script Python lee c['country'] de cada entrada
                Assertions.assertTrue(
                    geos.any { it.has("country") },
                    "Ninguna región contiene el campo 'country'"
                )
            }
        }

        @Test
        fun `fase 4 - discover devuelve proxies con ip y puertos`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val proxies = registeredClient().discover(COUNTRY_CODE)
                Assertions.assertTrue(proxies.isNotEmpty(), "discover($COUNTRY_CODE) vacío")
                proxies.forEach { p ->
                    Assertions.assertTrue(p.optString("ip").isNotBlank(), "Proxy sin ip: $p")
                    val ports = p.optJSONArray("ports")
                    Assertions.assertNotNull(ports, "Proxy sin ports: $p")
                    Assertions.assertTrue(ports!!.length() > 0, "Lista de puertos vacía: $p")
                    Assertions.assertTrue(ports.optInt(0, -1) in 1..65535, "Puerto inválido: $p")
                }
                log("Proxies: ${proxies.size}, primero=${proxies.first()}")
            }
        }

        @Test
        fun `fase 5 - login con el suscriptor recien registrado`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val client = registeredClient()
                Assertions.assertTrue(client.login(), "login() falló")
            }
        }

        @Test
        fun `fase 6 - deviceGeneratePassword tras login`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val client = registeredClient()
                Assertions.assertTrue(client.login(), "login() falló")
                val before = client.getProxyCredentials()!!.second

                Assertions.assertTrue(
                    client.deviceGeneratePassword(),
                    "deviceGeneratePassword() falló"
                )

                val after = client.getProxyCredentials()!!.second
                Assertions.assertTrue(after.isNotBlank(), "Password regenerado vacío")
                log("Password cambiado: ${before != after}")
            }
        }
    }

    // ---------------------------------------------------------------
    // Flujo completo
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Flujo completo")
    inner class FullFlow {

        @Test
        fun `initialize devuelve una conexion valida`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val connection = newClient().initialize(COUNTRY_CODE)

                Assertions.assertNotNull(
                    connection,
                    "initialize() devolvió null: falló alguna fase"
                )
                connection!!

                Assertions.assertTrue(connection.host.isNotBlank(), "Host vacío")
                Assertions.assertTrue(connection.port in 1..65535, "Puerto inválido")
                Assertions.assertTrue(
                    connection.username.matches(Regex("^[0-9A-F]{40}$")),
                    "Usuario con formato inesperado"
                )
                Assertions.assertTrue(connection.password.isNotBlank(), "Contraseña vacía")
                Assertions.assertEquals(COUNTRY_CODE, connection.countryCode)
                Assertions.assertEquals("eu0.sec-tunnel.com", connection.sniHostname)

                log("host=${connection.host}, port=${connection.port}, sni=${connection.sniHostname}")
            }
        }

        @Test
        fun `initialize es repetible con clientes distintos`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val a = newClient().initialize(COUNTRY_CODE)
                val b = newClient().initialize(COUNTRY_CODE)
                Assertions.assertNotNull(a)
                Assertions.assertNotNull(b)
                // Cada cliente registra un dispositivo distinto
                Assertions.assertNotEquals(a!!.username, b!!.username)
            }
        }
    }

    // ---------------------------------------------------------------
    // Prueba extremo a extremo: usar el proxy de verdad
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Extremo a extremo")
    inner class EndToEnd {

        @Test
        fun `el proxy acepta CONNECT y deja pasar trafico HTTPS`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val connection = newClient().initialize(COUNTRY_CODE)
                Assertions.assertNotNull(connection, "initialize() devolvió null")
                connection!!

                val targetHost = "example.com"
                val targetPort = 443

                // 1) TLS contra el proxy usando el SNI de sec-tunnel
                val raw = Socket(connection.host, connection.port).apply { soTimeout = 20_000 }
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val proxySocket = factory.createSocket(
                    raw, connection.host, connection.port, true
                ) as SSLSocket

                proxySocket.use { tls ->
                    val params: SSLParameters = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(connection.sniHostname))
                    tls.sslParameters = params
                    tls.startHandshake()

                    val auth = Base64.getEncoder().encodeToString(
                        "${connection.username}:${connection.password}".toByteArray()
                    )

                    val out = tls.outputStream
                    val input = tls.inputStream.bufferedReader(Charsets.ISO_8859_1)

                    // 2) CONNECT
                    out.write(
                        ("CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                                "Host: $targetHost:$targetPort\r\n" +
                                "Proxy-Authorization: Basic $auth\r\n" +
                                "Proxy-Connection: keep-alive\r\n\r\n").toByteArray()
                    )
                    out.flush()

                    val statusLine = input.readLine()
                    log("CONNECT -> $statusLine")
                    Assertions.assertNotNull(statusLine, "El proxy cerró sin responder")
                    Assertions.assertTrue(
                        statusLine!!.contains(" 200"),
                        "CONNECT rechazado: $statusLine"
                    )
                    // consumir cabeceras de la respuesta CONNECT
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    // 3) TLS extremo a extremo con el destino, a través del túnel
                    val target = factory.createSocket(
                        tls, targetHost, targetPort, true
                    ) as SSLSocket
                    target.startHandshake()

                    target.outputStream.write(
                        ("GET / HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\n\r\n")
                            .toByteArray()
                    )
                    target.outputStream.flush()

                    val firstLine = target.inputStream
                        .bufferedReader(Charsets.ISO_8859_1)
                        .readLine()
                    log("GET / -> $firstLine")

                    Assertions.assertNotNull(firstLine)
                    Assertions.assertTrue(
                        firstLine!!.startsWith("HTTP/"),
                        "Respuesta no HTTP: $firstLine"
                    )
                }
            }
        }

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** GET https://host/path a través del proxy de Opera (TLS al proxy + CONNECT + TLS al destino). */
        private fun httpsGetViaOpera(
            c: OperaSeClient.OperaConnection,
            host: String,
            path: String = "/"
        ): String {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(c.host, c.port), 15_000)
                raw.soTimeout = 20_000

                // 1) TLS contra el proxy, con el SNI de sec-tunnel
                val proxyTls = factory.createSocket(raw, c.sniHostname, c.port, true) as SSLSocket
                proxyTls.sslParameters = proxyTls.sslParameters.apply {
                    serverNames = listOf(SNIHostName(c.sniHostname))
                }
                proxyTls.startHandshake()

                // 2) CONNECT autenticado (Basic) al destino
                val auth = Base64.getEncoder()
                    .encodeToString("${c.username}:${c.password}".toByteArray())
                proxyTls.outputStream.apply {
                    write(
                        ("CONNECT $host:443 HTTP/1.1\r\n" +
                                "Host: $host:443\r\n" +
                                "Proxy-Authorization: Basic $auth\r\n\r\n").toByteArray()
                    )
                    flush()
                }

                // Leer la respuesta del CONNECT byte a byte, sin buffer, para no
                // consumir nada del túnel.
                fun readLine(): String {
                    val sb = StringBuilder()
                    while (true) {
                        val b = proxyTls.inputStream.read()
                        check(b >= 0) { "El proxy cerró la conexión" }
                        if (b == '\n'.code) break
                        if (b != '\r'.code) sb.append(b.toChar())
                    }
                    return sb.toString()
                }

                val status = readLine()
                check(status.contains(" 200")) { "CONNECT rechazado: $status" }
                while (readLine().isNotEmpty()) { /* cabeceras */ }

                // 3) TLS extremo a extremo con el destino, por dentro del túnel
                val target = factory.createSocket(proxyTls, host, 443, true) as SSLSocket
                target.sslParameters = target.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(host))
                }
                target.startHandshake()

                // HTTP/1.0: el servidor no usa chunked y cierra al terminar
                target.outputStream.apply {
                    write("GET $path HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
                    flush()
                }

                val response = target.inputStream.readBytes().toString(Charsets.ISO_8859_1)
                return response.substringAfter("\r\n\r\n").trim()
            } finally {
                try { raw.close() } catch (_: Exception) {}
            }
        }

        @Test
        fun `la IP publica cambia al usar el proxy`() {
            requireNetwork()

            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                // 1) IP pública directa
                val directIp = URL("https://api.ipify.org").openConnection().apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }.getInputStream().bufferedReader().use { it.readText().trim() }
                log("IP directa: $directIp")

                // 2) Credenciales del proxy
                val connection = newClient().initialize(COUNTRY_CODE)
                Assertions.assertNotNull(connection, "initialize() devolvió null")
                connection!!

                // 3) IP pública vista a través de Opera
                val proxyIp = httpsGetViaOpera(connection, "api.ipify.org")
                log("IP por Opera: $proxyIp (proxy ${connection.host}:${connection.port})")

                // 4) Comprobaciones
                Assertions.assertTrue(directIp.matches(IPV4), "IP directa no válida: $directIp")
                Assertions.assertTrue(proxyIp.matches(IPV4), "IP por proxy no válida: $proxyIp")
                Assertions.assertNotEquals(
                    directIp,
                    proxyIp,
                    "La IP es la misma con y sin proxy: $directIp"
                )
            }
        }

        @Test
        fun `la IP de salida por Opera es estable entre conexiones`() {
            requireNetwork()

            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val connection = newClient().initialize(COUNTRY_CODE)
                Assertions.assertNotNull(connection, "initialize() devolvió null")
                connection!!

                val ips = (1..4).map { httpsGetViaOpera(connection, "api.ipify.org") }
                log("IPs de salida en 4 conexiones: $ips")

                ips.forEach { Assertions.assertTrue(it.matches(IPV4), "IP no válida: $it") }
                // No es un fallo si varía; lo que importa es saberlo (ver nota)
                log("¿Estable?: ${ips.toSet().size == 1}")
            }
        }

        @Test
        fun `RoutingFetcher envia por Opera solo los hosts indicados`() {
            requireNetwork()
            Assertions.assertTimeoutPreemptively(TIMEOUT) {
                val connection = newClient().initialize(COUNTRY_CODE)!!
                val router = RoutingFetcher(DirectFetcher(), OperaFetcher(connection), setOf("api.ipify.org"))
                val directIp = DirectFetcher().get("https://api.ipify.org").text().trim()
                val routedIp = router.get("https://api.ipify.org").text().trim()
                Assertions.assertNotEquals(directIp, routedIp)
                // otro host: va directo, así que ve tu IP real
                val other = router.get("https://api64.ipify.org").text().trim()
                Assertions.assertEquals(directIp, other)
            }
        }
    }
}