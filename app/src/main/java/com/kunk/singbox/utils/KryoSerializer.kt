package com.kunk.singbox.utils

import android.util.Log
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.CollectionSerializer
import com.esotericsoftware.kryo.serializers.MapSerializer
import com.kunk.singbox.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Kryo 二进制序列化工具
 * 相比 JSON 序列化速度快 5-10x，体积小 50-70%
 */
object KryoSerializer {
    private const val TAG = "KryoSerializer"

    // 使用 ThreadLocal 保证线程安全，Kryo 实例不是线程安全的
    private val kryoThreadLocal = object : ThreadLocal<Kryo>() {
        override fun initialValue(): Kryo {
            return createKryo()
        }
    }

    private fun createKryo(): Kryo {
        return Kryo().apply {
            // 允许未注册的类（更灵活，但稍慢）
            isRegistrationRequired = false

            // 注册常用类以获得更好的性能
            register(SavedProfilesData::class.java)
            register(ProfileUi::class.java)
            register(ProfileType::class.java)
            register(UpdateStatus::class.java)
            register(NodeUi::class.java)
            register(SingBoxConfig::class.java)
            register(Outbound::class.java)
            register(TlsConfig::class.java)
            register(TransportConfig::class.java)
            register(UtlsConfig::class.java)
            register(RealityConfig::class.java)
            register(EchConfig::class.java)
            register(ObfsConfig::class.java)
            register(MultiplexConfig::class.java)
            register(WireGuardPeer::class.java)

            // 注册集合类型
            register(ArrayList::class.java)
            register(HashMap::class.java)
            register(LinkedHashMap::class.java)
            register(emptyList<Any>().javaClass)
            register(emptyMap<Any, Any>().javaClass)

            // 设置引用跟踪（避免循环引用问题）
            references = true
        }
    }

    private val kryo: Kryo
        get() = kryoThreadLocal.get()!!

    /**
     * 序列化对象到字节数组
     */
    fun <T> serialize(obj: T): ByteArray {
        val outputStream = ByteArrayOutputStream(4096)
        Output(outputStream).use { output ->
            kryo.writeClassAndObject(output, obj)
        }
        return outputStream.toByteArray()
    }

    /**
     * 从字节数组反序列化对象
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserialize(bytes: ByteArray): T? {
        return try {
            Input(ByteArrayInputStream(bytes)).use { input ->
                kryo.readClassAndObject(input) as? T
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deserialization failed", e)
            null
        }
    }

    /**
     * 序列化对象到文件
     */
    fun <T> serializeToFile(obj: T, file: File): Boolean {
        return try {
            val tmpFile = File(file.parent, "${file.name}.tmp")
            tmpFile.outputStream().use { fos ->
                Output(fos, 8192).use { output ->
                    kryo.writeClassAndObject(output, obj)
                }
            }

            // 原子替换
            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (file.exists()) {
                    file.delete()
                }
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                true
            } else {
                Log.e(TAG, "Serialization produced empty file")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize to file: ${file.name}", e)
            false
        }
    }

    /**
     * 从文件反序列化对象
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserializeFromFile(file: File): T? {
        if (!file.exists()) return null

        return try {
            file.inputStream().use { fis ->
                Input(fis, 8192).use { input ->
                    kryo.readClassAndObject(input) as? T
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize from file: ${file.name}", e)
            null
        }
    }

    /**
     * 检查文件是否为 Kryo 格式（通过魔数判断）
     * Kryo 文件没有固定魔数，但 JSON 文件通常以 { 或 [ 开头
     */
    fun isKryoFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1) return false

        return try {
            file.inputStream().use { fis ->
                val firstByte = fis.read()
                // JSON 文件通常以 '{' (123) 或 '[' (91) 或空白字符开头
                firstByte != '{'.code && firstByte != '['.code &&
                firstByte != ' '.code && firstByte != '\n'.code &&
                firstByte != '\r'.code && firstByte != '\t'.code
            }
        } catch (e: Exception) {
            false
        }
    }
}
