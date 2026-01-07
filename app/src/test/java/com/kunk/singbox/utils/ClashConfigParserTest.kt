package com.kunk.singbox.utils

import com.google.gson.GsonBuilder
import com.kunk.singbox.utils.parser.ClashYamlParser
import org.junit.Assert.*
import org.junit.Test

class ClashConfigParserTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // 使用别名以保持与旧代码的兼容性
    private object ClashConfigParser {
        fun parse(yaml: String) = ClashYamlParser().parse(yaml)
    }

    @Test
    fun testParseSimpleClashConfig() {
        val yaml = """
            proxies:
              - name: "ss1"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: "pass"
            proxy-groups:
              - name: "PROXY"
                type: select
                proxies:
                  - ss1
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)
        assertNotNull(config?.outbounds)
        
        val outbounds = config!!.outbounds!!
        assertEquals(5, outbounds.size) // ss1 + PROXY + direct + block + dns-out
        
        val ss1 = outbounds.find { it.tag == "ss1" }
        assertNotNull(ss1)
        assertEquals("shadowsocks", ss1?.type)
        assertEquals("1.2.3.4", ss1?.server)
        
        val proxyGroup = outbounds.find { it.tag == "PROXY" }
        assertNotNull(proxyGroup)
        assertEquals("selector", proxyGroup?.type)
        assertTrue(proxyGroup?.outbounds?.contains("ss1") == true)
    }

    @Test
    fun testParseVLessWithReality() {
        val yaml = """
            proxies:
              - name: "vless-reality"
                type: vless
                server: example.com
                port: 443
                uuid: uuid-123
                network: ws
                ws-opts:
                  path: /path?ed=2048
                  headers:
                    Host: example.com
                tls: true
                reality-opts:
                  public-key: "pbk"
                  short-id: "sid"
                client-fingerprint: chrome
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)
        
        val vless = config?.outbounds?.find { it.tag == "vless-reality" }
        assertNotNull(vless)
        assertEquals("vless", vless?.type)
        assertNotNull(vless?.tls)
        assertEquals(true, vless?.tls?.enabled)
        assertNotNull(vless?.tls?.reality)
        assertEquals("pbk", vless?.tls?.reality?.publicKey)
        assertEquals("chrome", vless?.tls?.utls?.fingerprint)
        
        assertNotNull(vless?.transport)
        assertEquals("ws", vless?.transport?.type)
        assertEquals("/path?ed=2048", vless?.transport?.path)
    }

    @Test
    fun testParseHttpWithTls() {
        val yaml = """
            proxies:
              - name: "美国西雅图"
                port: 443
                server: proxy.example.com
                tls: true
                type: http
                username: user123
                password: pass456
                skip-cert-verify: true
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val http = config?.outbounds?.find { it.tag == "美国西雅图" }
        assertNotNull(http)
        assertEquals("http", http?.type)
        assertEquals("proxy.example.com", http?.server)
        assertEquals(443, http?.serverPort)
        assertEquals("user123", http?.username)
        assertEquals("pass456", http?.password)

        // TLS 配置验证
        assertNotNull(http?.tls)
        assertEquals(true, http?.tls?.enabled)
        assertEquals("proxy.example.com", http?.tls?.serverName)
        assertEquals(true, http?.tls?.insecure)

        // 打印生成的 JSON 以便调试
        println("HTTP+TLS Outbound JSON:")
        println(gson.toJson(http))
    }
}
