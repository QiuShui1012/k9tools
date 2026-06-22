package io.github.tt432.k9tools

import com.intellij.psi.PsiTypeElement
import java.util.logging.Logger

private val LOGGER = Logger.getLogger("CodecFinder")

private const val Codec: String = "com.mojang.serialization.Codec"
private const val ExtraCodecs: String = "net.minecraft.util.ExtraCodecs"
private const val ByteBufCodecs: String = "net.minecraft.network.codec.ByteBufCodecs"

fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field), mapKey: Boolean = false): String {
    LOGGER.info("getCodecRef: typeName=$typeName, mapKey=$mapKey")

    val result = if (vanillaCodecClasses.contains(typeName)) {
        val idx = vanillaCodecClasses.indexOf(typeName)
        val fieldName = vanillaCodecFieldName[idx]
        LOGGER.info("  匹配 vanilla 基本类型: $typeName -> Codec.$fieldName")
        "$Codec.${fieldName}"
    } else if (vanillaKeywordCodec.contains(typeName)) {
        val idx = vanillaKeywordCodec.indexOf(typeName)
        val fieldName = vanillaCodecFieldName[idx]
        LOGGER.info("  匹配 Java 关键字: $typeName -> Codec.$fieldName")
        "$Codec.${fieldName}"
    } else if (vanillaExtraCodecs.containsKey(typeName)) {
        val fieldName = vanillaExtraCodecs[typeName]
        LOGGER.info("  匹配 ExtraCodecs: $typeName -> ExtraCodecs.$fieldName")
        "$ExtraCodecs.${fieldName}"
    } else if (mapKey && vanillaMapKeySpecialCodecs.containsKey(typeName)) {
        val codec = vanillaMapKeySpecialCodecs[typeName]
        LOGGER.info("  匹配 MapKey 特殊 Codec: $typeName -> $codec")
        "$codec"
    } else if (vanillaSpecialCodecs.containsKey(typeName)) {
        val codec = vanillaSpecialCodecs[typeName]
        LOGGER.info("  匹配特殊 Codec: $typeName -> $codec")
        "$codec"
    } else when (typeName) {
        "java.util.List" -> {
            val fieldGeneric = getFieldGeneric(field)
            LOGGER.info("  处理 List，泛型参数数量: ${fieldGeneric.size}")
            if (fieldGeneric.isNotEmpty()) {
                val elementCodec = getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                val resultList = "${elementCodec}\n.listOf()"
                LOGGER.info("  List Codec: $resultList")
                return resultList
            }
            LOGGER.info("  List 没有泛型参数，返回空字符串")
            ""
        }

        "java.util.Map" -> {
            val fieldGeneric = getFieldGeneric(field)
            LOGGER.info("  处理 Map，泛型参数数量: ${fieldGeneric.size}")
            if (fieldGeneric.isNotEmpty()) {
                val keyCodec = getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]), true)
                val valueCodec = getCodecRef(fieldGeneric[1], getTypeName(fieldGeneric[1]))
                val resultMap = "$Codec.unboundedMap($keyCodec, $valueCodec)"
                LOGGER.info("  Map Codec: $resultMap")
                return resultMap
            }
            LOGGER.info("  Map 没有泛型参数，返回空字符串")
            ""
        }

        "java.util.Optional" -> {
            val fieldGeneric = getFieldGeneric(field)
            LOGGER.info("  处理 Optional，泛型参数数量: ${fieldGeneric.size}")
            if (fieldGeneric.isNotEmpty()) {
                val innerCodec = getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                LOGGER.info("  Optional Codec: $innerCodec")
                return innerCodec
            }
            LOGGER.info("  Optional 没有泛型参数，返回空字符串")
            ""
        }

        else -> "$typeName.CODEC"
    }

    LOGGER.info("getCodecRef 结果: $result")
    return result
}

fun getStreamCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field), mapKey: Boolean = false): String {
    LOGGER.info("getStreamCodecRef: typeName=$typeName, mapKey=$mapKey")

    val result = if (vanillaStreamCodecClasses.contains(typeName)) {
        val idx = vanillaStreamCodecClasses.indexOf(typeName)
        val fieldName = vanillaStreamCodecFieldName[idx]
        LOGGER.info("  匹配 StreamCodec 基本类型: $typeName -> ByteBufCodecs.$fieldName")
        "$ByteBufCodecs.${fieldName}"
    } else if (vanillaKeywordCodec.contains(typeName)) {
        val idx = vanillaKeywordCodec.indexOf(typeName)
        val fieldName = vanillaStreamCodecFieldName[idx]
        LOGGER.info("  匹配 Java 关键字 StreamCodec: $typeName -> ByteBufCodecs.$fieldName")
        "$ByteBufCodecs.${fieldName}"
    } else if (mapKey && vanillaMapKeySpecialStreamCodecs.containsKey(typeName)) {
        val codec = vanillaMapKeySpecialStreamCodecs[typeName]
        LOGGER.info("  匹配 MapKey 特殊 StreamCodec: $typeName -> $codec")
        "$codec"
    } else if (vanillaSpecialStreamCodecs.containsKey(typeName)) {
        val codec = vanillaSpecialStreamCodecs[typeName]
        LOGGER.info("  匹配特殊 StreamCodec: $typeName -> $codec")
        "$codec"
    } else when (typeName) {
        "java.util.List" -> {
            val fieldGeneric = getFieldGeneric(field)
            LOGGER.info("  处理 List，泛型参数数量: ${fieldGeneric.size}")
            if (fieldGeneric.isNotEmpty()) {
                val elementCodec = getStreamCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                val resultList = "${elementCodec}.apply($ByteBufCodecs.list())"
                LOGGER.info("  List StreamCodec: $resultList")
                return resultList
            }
            LOGGER.info("  List 没有泛型参数，返回空字符串")
            ""
        }

        "java.util.Map" -> {
            val fieldGeneric = getFieldGeneric(field)
            LOGGER.info("  处理 Map，泛型参数数量: ${fieldGeneric.size}")
            if (fieldGeneric.isNotEmpty()) {
                val keyCodec = getStreamCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]), true)
                val valueCodec = getStreamCodecRef(fieldGeneric[1], getTypeName(fieldGeneric[1]))
                val resultMap = "$ByteBufCodecs.map(java.util.HashMap::new, $keyCodec, $valueCodec)"
                LOGGER.info("  Map StreamCodec: $resultMap")
                return resultMap
            }
            LOGGER.info("  Map 没有泛型参数，返回空字符串")
            ""
        }

        "java.util.Optional" -> {
            val fieldGeneric = getFieldGeneric(field)
            LOGGER.info("  处理 Optional，泛型参数数量: ${fieldGeneric.size}")
            if (fieldGeneric.isNotEmpty()) {
                val innerCodec = getStreamCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                val resultOptional = "${innerCodec}.apply($ByteBufCodecs::optional)"
                LOGGER.info("  Optional StreamCodec: $resultOptional")
                return resultOptional
            }
            LOGGER.info("  Optional 没有泛型参数，返回空字符串")
            ""
        }

        else -> {
            LOGGER.info("  使用默认 StreamCodec: $typeName.STREAM_CODEC")
            "$typeName.STREAM_CODEC"
        }
    }

    LOGGER.info("getStreamCodecRef 结果: $result")
    return result
}

val vanillaCodecClasses = listOf(
    "java.lang.Boolean",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.String",
    "java.nio.ByteBuffer",
    "java.util.stream.IntStream",
    "java.util.stream.LongStream"
)

private val vanillaStreamCodecClasses = listOf(
    "java.lang.Boolean",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.String",
    "net.minecraft.nbt.Tag",
    "net.minecraft.nbt.CompoundTag",
    "org.joml.Vector3f",
    "org.joml.Quaternionf",
    "com.mojang.authlib.properties.PropertyMap",
    "com.mojang.authlib.GameProfile",
    "byte[]",
    "long[]"
)

val vanillaKeywordCodec = listOf(
    "boolean",
    "byte",
    "short",
    "int",
    "long",
    "float",
    "double"
)

private val vanillaCodecFieldName = listOf(
    "BOOL",
    "BYTE",
    "SHORT",
    "INT",
    "LONG",
    "FLOAT",
    "DOUBLE",
    "STRING",
    "BYTE_BUFFER",
    "INT_STREAM",
    "LONG_STREAM"
)

private val vanillaStreamCodecFieldName = listOf(
    "BOOL",
    "BYTE",
    "SHORT",
    "VAR_INT",
    "VAR_LONG",
    "FLOAT",
    "DOUBLE",
    "STRING_UTF8",
    "TAG",
    "COMPOUND_TAG",
    "VECTOR3F",
    "QUATERNIONF",
    "GAME_PROFILE_PROPERTIES",
    "GAME_PROFILE",
    "BYTE_ARRAY",
    "LONG_ARRAY"
)

private val vanillaExtraCodecs = mapOf(
    Pair("com.google.gson.JsonElement", "JSON"),
    Pair("java.lang.Object", "JAVA"),
    Pair("net.minecraft.nbt.Tag", "NBT"),
    Pair("org.joml.Vector2fc", "VECTOR2F"),
    Pair("org.joml.Vector3fc", "VECTOR3F"),
    Pair("org.joml.Vector3ic", "VECTOR3I"),
    Pair("org.joml.Vector4fc", "VECTOR4F"),
    Pair("org.joml.Quaternionfc", "QUATERNIONF"),
    Pair("org.joml.AxisAngle4f", "AXISANGLE4F"),
    Pair("org.joml.Matrix4fc", "MATRIX4F"),
    Pair("java.util.regex.Pattern", "PATTERN"),
    Pair("java.time.Instant", "INSTANT_ISO8601"),
    Pair("net.minecraft.util.ExtraCodecs.TagOrElementLocation", "TAG_OR_ELEMENT_ID"),
    Pair("java.util.BitSet", "BIT_SET"),
    Pair("com.mojang.authlib.properties.Property", "PROPERTY"),
    Pair("java.net.URI", "UNTRUSTED_URI")
)

private val vanillaMapKeySpecialCodecs = mapOf(
    Pair("java.util.UUID", "net.minecraft.core.UUIDUtil.STRING_CODEC"),
)

private val vanillaSpecialCodecs = mapOf(
    Pair("java.util.UUID", "net.minecraft.core.UUIDUtil.CODEC"),
    Pair("net.minecraft.network.chat.Component", "net.minecraft.network.chat.ComponentSerialization.CODEC"),
)

private val vanillaMapKeySpecialStreamCodecs = mapOf<String, String>(
)

private val vanillaSpecialStreamCodecs = mapOf(
    Pair("java.util.UUID", "net.minecraft.core.UUIDUtil.STREAM_CODEC"),
    Pair("net.minecraft.network.chat.Component", "net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC"),
)
