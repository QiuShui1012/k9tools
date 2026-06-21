package io.github.tt432.k9tools

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.GlobalSearchScope
import java.util.logging.Logger

/**
 * @author QiuShui1012
 */

private val LOGGER = Logger.getLogger("ByteBufFinder")

/**
 * 从字段列表中获取最终的 ByteBuf 类型
 * 遍历所有字段，通过 findByteBufType 获取每个字段的 ByteBuf 类型
 * 然后找出继承链中最底层的 ByteBuf 子类
 * 如果有多个不同的子类，返回 io.netty.buffer.ByteBuf
 *
 * @param fields 字段数组
 * @param project 当前项目
 * @return 最终的 ByteBuf 类型的全限定类名
 */
fun getFinalByteBufType(fields: Array<out PsiField>, project: Project): String {
    LOGGER.info("=== getFinalByteBufType 开始 ===")
    LOGGER.info("字段数量: ${fields.size}")

    if (fields.isEmpty()) {
        LOGGER.info("字段列表为空，返回默认 ByteBuf")
        return "io.netty.buffer.ByteBuf"
    }

    // 收集所有 ByteBuf 类型
    val byteBufTypes = mutableListOf<PsiType>()
    for ((index, field) in fields.withIndex()) {
        val typeText = field.type.canonicalText
        LOGGER.info("处理字段 #$index: ${field.name} ($typeText)")
        val byteBufType = findByteBufType(field, project)
        if (byteBufType is PsiClassType) {
            val resolved = byteBufType.resolve()
            val typeName = byteBufType.canonicalText
            if (resolved != null && isByteBufType(byteBufType)) {
                LOGGER.info("  -> 找到 ByteBuf 类型: $typeName, 类: ${resolved.qualifiedName}")
                byteBufTypes.add(byteBufType)
            } else {
                LOGGER.info("  -> 跳过类型: $typeName, resolved=${resolved != null}, isByteBuf=${if (resolved != null) isByteBufType(byteBufType) else false}")
            }
        } else {
            LOGGER.info("  -> 不是 PsiClassType: ${byteBufType.javaClass.simpleName}")
        }
    }

    if (byteBufTypes.isEmpty()) {
        LOGGER.info("未找到任何 ByteBuf 类型，返回默认 ByteBuf")
        return "io.netty.buffer.ByteBuf"
    }

    LOGGER.info("收集到的 ByteBuf 类型数量: ${byteBufTypes.size}")
    byteBufTypes.forEach { LOGGER.info("  - ${it.canonicalText}") }

    // 如果只有一个类型，直接返回
    if (byteBufTypes.size == 1) {
        val result = byteBufTypes[0].canonicalText
        LOGGER.info("只有一个类型，返回: $result")
        return result
    }

    // 构建继承层级
    val typeHierarchy = mutableMapOf<String, MutableSet<String>>()
    val classNameToPsiClass = mutableMapOf<String, PsiClass>()

    for (type in byteBufTypes) {
        if (type !is PsiClassType) {
            LOGGER.warning("跳过非 PsiClassType: ${type.javaClass.simpleName}")
            continue
        }

        val typeName = type.canonicalText
        val psiClass = type.resolve()
        if (psiClass == null) {
            LOGGER.warning("无法解析类型: $typeName")
            continue
        }

        LOGGER.info("构建继承层级: $typeName")
        classNameToPsiClass[typeName] = psiClass

        // 获取该类型的所有父类
        val superTypes = mutableSetOf<String>()
        var superClass = psiClass.superClass
        var depth = 0
        while (superClass != null && depth < 50) {
            val superName = superClass.qualifiedName
            if (superName != null) {
                LOGGER.info("  -> 父类 #$depth: $superName")
                superTypes.add(superName)
            }
            superClass = superClass.superClass
            depth++
        }

        // 也检查实现的接口
        val interfaces = psiClass.interfaces.map { it.qualifiedName }.filterNotNull()
        LOGGER.info("  -> 实现的接口: ${interfaces.joinToString()}")
        superTypes.addAll(interfaces)

        typeHierarchy[typeName] = superTypes
        LOGGER.info("  -> 完成: $typeName 的父类/接口: ${superTypes.joinToString()}")
    }

    // 找到最底层的类型（没有子类在集合中的类型）
    val allTypes = typeHierarchy.keys.toSet()
    LOGGER.info("所有类型: ${allTypes.joinToString()}")

    val deepestTypes = mutableSetOf<String>()

    for ((type, _) in typeHierarchy) {
        LOGGER.info("检查类型 $type 是否有子类...")
        var hasChild = false
        for (otherType in allTypes) {
            if (otherType == type) continue
            val otherSuperTypes = typeHierarchy[otherType]
            if (otherSuperTypes != null && otherSuperTypes.contains(type)) {
                LOGGER.info("  -> $otherType 是 $type 的子类")
                hasChild = true
                break
            }
        }
        if (!hasChild) {
            LOGGER.info("  -> $type 是最底层类型")
            deepestTypes.add(type)
        }
    }

    LOGGER.info("最底层类型: ${deepestTypes.joinToString()}")

    // 如果有多个最底层类型（继承链冲突），返回 ByteBuf
    if (deepestTypes.size != 1) {
        LOGGER.info("多个最底层类型或没有最底层类型，返回默认 ByteBuf")
        return "io.netty.buffer.ByteBuf"
    }

    val result = deepestTypes.firstOrNull() ?: "io.netty.buffer.ByteBuf"
    LOGGER.info("最终结果: $result")
    return result
}

/**
 * 从字段中提取 StreamCodec 的 ByteBuf 泛型类型
 * 查找名为 STREAM_CODEC 的常量字段，提取其泛型参数 B（即 ByteBuf 类型）
 * 如果未找到，返回 ByteBuf 类型
 *
 * @param field 要检查的字段
 * @param project 当前项目
 * @return 表示 ByteBuf 类型的 PsiType
 */
private fun findByteBufType(field: PsiField, project: Project): PsiType {
    val typeText = field.type.canonicalText
    LOGGER.info("findByteBufType: 字段=${field.name}, 类型=$typeText")

    val fieldType = field.type
    if (fieldType !is PsiClassType) {
        LOGGER.info("  -> 字段类型不是 PsiClassType，返回默认 ByteBuf")
        return getByteBufType(project)
    }

    val resolvedClass = fieldType.resolve()
    if (resolvedClass == null) {
        LOGGER.info("  -> 无法解析类，返回默认 ByteBuf")
        return getByteBufType(project)
    }

    LOGGER.info("  -> 解析到类: ${resolvedClass.qualifiedName}")

    // 1. 查找名为 STREAM_CODEC 的静态常量字段
    val codecField = resolvedClass.allFields.firstOrNull {
        it.name == "STREAM_CODEC" &&
        it.hasModifierProperty(PsiModifier.STATIC) &&
        it.hasModifierProperty(PsiModifier.FINAL)
    }

    if (codecField != null) {
        val codecTypeText = codecField.type.canonicalText
        LOGGER.info("  -> 找到 STREAM_CODEC 字段: $codecTypeText")
        val codecType = codecField.type
        if (codecType is PsiClassType) {
            // 直接从 STREAM_CODEC 字段的类型中提取泛型参数
            val parameters = codecType.parameters
            LOGGER.info("  -> STREAM_CODEC 泛型参数数量: ${parameters.size}")
            for ((i, param) in parameters.withIndex()) {
                LOGGER.info("  ->   参数 #$i: ${param.canonicalText}")
            }
            if (parameters.isNotEmpty()) {
                val firstParam = parameters[0]
                LOGGER.info("  -> 第一个参数: ${firstParam.canonicalText}")
                if (isByteBufType(firstParam)) {
                    LOGGER.info("  -> 第一个参数是 ByteBuf 子类型: ${firstParam.canonicalText}")
                    return firstParam
                }
            }

            // 如果直接提取失败，再尝试通过 StreamCodec 类提取
            val codecClass = codecType.resolve()
            if (codecClass != null && isStreamCodecType(codecClass)) {
                LOGGER.info("  -> STREAM_CODEC 是 StreamCodec 类型，尝试从类中提取")
                val byteBufType = extractByteBufFromStreamCodec(codecClass, project)
                if (byteBufType != null && isByteBufType(byteBufType)) {
                    LOGGER.info("  -> 提取到 ByteBuf 类型: ${byteBufType.canonicalText}")
                    return byteBufType
                }
            }
        }
    } else {
        LOGGER.info("  -> 未找到 STREAM_CODEC 字段")
    }

    // 2. 检查类本身是否实现了 StreamCodec，直接提取泛型参数
    LOGGER.info("  -> 检查类是否实现 StreamCodec...")
    val streamCodecByteBuf = findStreamCodecByteBuf(resolvedClass, project)
    if (streamCodecByteBuf != null) {
        LOGGER.info("  -> 从 StreamCodec 提取到: ${streamCodecByteBuf.canonicalText}")
        return streamCodecByteBuf
    }

    // 3. 都未找到，返回 ByteBuf
    LOGGER.info("  -> 未找到 StreamCodec，返回默认 ByteBuf")
    return getByteBufType(project)
}

/**
 * 判断类型是否为 ByteBuf 或其子类
 * 此方法为内部使用，已从 private 改为 internal 以便在 getFinalByteBufType 中使用
 */
internal fun isByteBufType(type: PsiType): Boolean {
    if (type !is PsiClassType) {
        LOGGER.fine("isByteBufType: 不是 PsiClassType")
        return false
    }

    val psiClass = type.resolve()
    if (psiClass == null) {
        LOGGER.fine("isByteBufType: 无法解析类")
        return false
    }

    val qualifiedName = psiClass.qualifiedName
    if (qualifiedName == null) {
        LOGGER.fine("isByteBufType: qualifiedName 为空")
        return false
    }

    // 直接匹配 ByteBuf
    if (qualifiedName == "io.netty.buffer.ByteBuf") {
        LOGGER.fine("isByteBufType: $qualifiedName 直接匹配 ByteBuf")
        return true
    }

    // 检查是否继承自 ByteBuf
    var superClass = psiClass.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        val superName = superClass.qualifiedName
        if (superName == "io.netty.buffer.ByteBuf") {
            LOGGER.fine("isByteBufType: $qualifiedName 继承自 ByteBuf (通过 $superName)")
            return true
        }
        superClass = superClass.superClass
        depth++
    }

    LOGGER.fine("isByteBufType: $qualifiedName 不是 ByteBuf 子类")
    return false
}

/**
 * 查找类继承链中的 StreamCodec，返回其 ByteBuf 泛型类型
 */
private fun findStreamCodecByteBuf(psiClass: PsiClass, project: Project): PsiType? {
    LOGGER.fine("findStreamCodecByteBuf: 类=${psiClass.qualifiedName}")

    // 检查当前类是否直接实现了 StreamCodec
    for (interfaceClass in psiClass.interfaces) {
        LOGGER.fine("  -> 检查接口: ${interfaceClass.qualifiedName}")
        val byteBufType = extractByteBufFromStreamCodec(interfaceClass, project)
        if (byteBufType != null) {
            LOGGER.fine("  -> 从接口提取到: ${byteBufType.canonicalText}")
            return byteBufType
        }
    }

    // 检查父类
    var superClass = psiClass.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        LOGGER.fine("  -> 检查父类 #$depth: ${superClass.qualifiedName}")
        // 检查父类本身是否为 StreamCodec
        val byteBufType = extractByteBufFromStreamCodec(superClass, project)
        if (byteBufType != null) {
            LOGGER.fine("  -> 从父类提取到: ${byteBufType.canonicalText}")
            return byteBufType
        }

        // 检查父类实现的接口
        for (interfaceClass in superClass.interfaces) {
            val byteBufTypeFromInterface = extractByteBufFromStreamCodec(interfaceClass, project)
            if (byteBufTypeFromInterface != null) {
                LOGGER.fine("  -> 从父类接口提取到: ${byteBufTypeFromInterface.canonicalText}")
                return byteBufTypeFromInterface
            }
        }
        superClass = superClass.superClass
        depth++
    }

    return null
}

/**
 * 从 StreamCodec 类中提取 ByteBuf 泛型参数
 * StreamCodec 的定义为：StreamCodec<B, V>
 * 其中 B 是 ByteBuf 类型，V 是值类型
 */
private fun extractByteBufFromStreamCodec(streamCodecClass: PsiClass, project: Project): PsiType? {
    LOGGER.info("extractByteBufFromStreamCodec: 类=${streamCodecClass.qualifiedName}")

    if (!isStreamCodecType(streamCodecClass)) {
        LOGGER.info("  -> 不是 StreamCodec 类型")
        return null
    }

    // 方法1: 检查 STREAM_CODEC 字段的类型（最直接的方式）
    val codecField = streamCodecClass.allFields.firstOrNull {
        it.name == "STREAM_CODEC" &&
        it.hasModifierProperty(PsiModifier.STATIC) &&
        it.hasModifierProperty(PsiModifier.FINAL)
    }
    if (codecField != null) {
        val codecTypeText = codecField.type.canonicalText
        LOGGER.info("  -> 找到 STREAM_CODEC 字段: $codecTypeText")
        val codecType = codecField.type
        if (codecType is PsiClassType) {
            val parameters = codecType.parameters
            LOGGER.info("  -> STREAM_CODEC 泛型参数数量: ${parameters.size}")
            for ((i, param) in parameters.withIndex()) {
                LOGGER.info("  ->   参数 #$i: ${param.canonicalText}")
            }
            if (parameters.isNotEmpty()) {
                val firstParam = parameters[0]
                LOGGER.info("  -> 第一个参数类型: ${firstParam.javaClass.simpleName}, canonicalText: ${firstParam.canonicalText}")

                // 检查第一个参数是否是 ByteBuf 类型
                if (isByteBufType(firstParam)) {
                    LOGGER.info("  -> 第一个参数是 ByteBuf 子类型: ${firstParam.canonicalText}")
                    return firstParam
                }

                // 如果第一个参数是类型参数（比如 B），尝试解析其实际类型
                if (firstParam is PsiClassType) {
                    val resolved = firstParam.resolve()
                    if (resolved is PsiTypeParameter) {
                        LOGGER.info("  -> 第一个参数是类型参数: ${resolved.name}")
                        // 查找该类型参数在类中的绑定
                        val actualType = findTypeParameterBinding(resolved, streamCodecClass)
                        if (actualType != null && isByteBufType(actualType)) {
                            LOGGER.info("  -> 绑定到: ${actualType.canonicalText}")
                            return actualType
                        }
                    } else {
                        LOGGER.info("  -> 第一个参数解析为: ${resolved?.qualifiedName}")
                    }
                }
            }
        }
    } else {
        LOGGER.info("  -> 未找到 STREAM_CODEC 字段")
    }

    // 方法2: 检查类自身是否使用 StreamCodec 作为泛型父类/接口
    val superTypes = streamCodecClass.supers
    LOGGER.info("  -> 检查父类型数量: ${superTypes.size}")
    for (superType in superTypes) {
        if (superType is PsiClassType) {
            val resolvedSuper = superType.resolve()
            if (resolvedSuper != null && isStreamCodecType(resolvedSuper)) {
                val parameters = superType.parameters
                LOGGER.info("  -> 找到 StreamCodec 父类型: ${superType.canonicalText}, 参数数量: ${parameters.size}")
                if (parameters.isNotEmpty()) {
                    val firstParam = parameters[0]
                    LOGGER.info("  -> 第一个参数: ${firstParam.canonicalText}")
                    if (isByteBufType(firstParam)) {
                        LOGGER.info("  -> 第一个参数是 ByteBuf: ${firstParam.canonicalText}")
                        return firstParam
                    }
                    // 如果第一个参数是类型变量，尝试在类中查找其实际绑定
                    if (firstParam is PsiClassType) {
                        val resolved = firstParam.resolve()
                        if (resolved is PsiTypeParameter) {
                            LOGGER.info("  -> 第一个参数是类型参数: ${resolved.name}")
                            val actualType = findTypeParameterBinding(resolved, streamCodecClass)
                            if (actualType != null && isByteBufType(actualType)) {
                                LOGGER.info("  -> 绑定到: ${actualType.canonicalText}")
                                return actualType
                            }
                        }
                    }
                }
            }
        }
    }

    // 方法3: 检查 implements/extends 列表
    val extendsList = streamCodecClass.extendsListTypes
    LOGGER.info("  -> 检查 extends 列表数量: ${extendsList.size}")
    for (extendsType in extendsList) {
        if (extendsType is PsiClassType) {
            val resolved = extendsType.resolve()
            if (resolved != null && isStreamCodecType(resolved)) {
                val parameters = extendsType.parameters
                LOGGER.info("  -> 找到 extends StreamCodec: ${extendsType.canonicalText}, 参数数量: ${parameters.size}")
                if (parameters.isNotEmpty()) {
                    val firstParam = parameters[0]
                    LOGGER.info("  -> 第一个参数: ${firstParam.canonicalText}")
                    if (isByteBufType(firstParam)) {
                        LOGGER.info("  -> 第一个参数是 ByteBuf: ${firstParam.canonicalText}")
                        return firstParam
                    }
                    if (firstParam is PsiClassType) {
                        val resolvedParam = firstParam.resolve()
                        if (resolvedParam is PsiTypeParameter) {
                            LOGGER.info("  -> 第一个参数是类型参数: ${resolvedParam.name}")
                            val actualType = findTypeParameterBinding(resolvedParam, streamCodecClass)
                            if (actualType != null && isByteBufType(actualType)) {
                                LOGGER.info("  -> 绑定到: ${actualType.canonicalText}")
                                return actualType
                            }
                        }
                    }
                }
            }
        }
    }

    // 方法4: 检查父类
    var superClass = streamCodecClass.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        LOGGER.info("  -> 递归检查父类 #$depth: ${superClass.qualifiedName}")
        if (isStreamCodecType(superClass)) {
            val byteBufType = extractByteBufFromStreamCodec(superClass, project)
            if (byteBufType != null && isByteBufType(byteBufType)) {
                LOGGER.info("  -> 从父类递归获取到: ${byteBufType.canonicalText}")
                return byteBufType
            }
        }
        superClass = superClass.superClass
        depth++
    }

    // 方法5: 检查实现的接口
    for (interfaceClass in streamCodecClass.interfaces) {
        LOGGER.info("  -> 检查接口: ${interfaceClass.qualifiedName}")
        if (isStreamCodecType(interfaceClass)) {
            val byteBufType = extractByteBufFromStreamCodec(interfaceClass, project)
            if (byteBufType != null && isByteBufType(byteBufType)) {
                LOGGER.info("  -> 从接口获取到: ${byteBufType.canonicalText}")
                return byteBufType
            }
        }
    }

    // 如果仍然找不到，返回默认的 ByteBuf 类型
    LOGGER.info("  -> 未找到具体 ByteBuf 类型，返回默认")
    return getByteBufType(project)
}

/**
 * 查找类型参数在类中的实际绑定类型
 * 例如：class MyCodec<B extends ByteBuf> implements StreamCodec<B, String>
 * 查找 B 的实际类型
 */
private fun findTypeParameterBinding(typeParameter: PsiTypeParameter, context: PsiClass): PsiType? {
    LOGGER.info("findTypeParameterBinding: 参数=${typeParameter.name}, 上下文=${context.qualifiedName}")

    // 1. 检查类型参数的边界（extends 限制）
    val bounds = typeParameter.extendsListTypes
    LOGGER.info("  -> 边界数量: ${bounds.size}")
    for (bound in bounds) {
        LOGGER.info("  -> 边界: ${bound.canonicalText}")
        if (isByteBufType(bound)) {
            LOGGER.info("  -> 边界是 ByteBuf: ${bound.canonicalText}")
            return bound
        }
        // 如果边界本身是类型参数，递归查找
        val resolvedBound = bound.resolve()
        if (resolvedBound is PsiTypeParameter) {
            val actualType = findTypeParameterBinding(resolvedBound, context)
            if (actualType != null) {
                LOGGER.info("  -> 通过边界递归找到: ${actualType.canonicalText}")
                return actualType
            }
        }
    }

    // 2. 检查当前类的类型参数中是否有该参数的实例化
    val superTypes = context.supers
    LOGGER.info("  -> 检查父类型的类型参数绑定, 父类型数量: ${superTypes.size}")
    for (superType in superTypes) {
        if (superType is PsiClassType) {
            val parameters = superType.parameters
            val typeParameters = superType.resolve()?.typeParameters
            if (typeParameters == null) continue

            LOGGER.info("  -> 父类型 ${superType.canonicalText} 的类型参数: ${typeParameters.joinToString { it.name.toString() }}, 实际参数: ${parameters.joinToString { it.canonicalText }}")

            for (i in typeParameters.indices) {
                val param = typeParameters[i]
                if (param.name == typeParameter.name) {
                    val actualType = parameters.getOrNull(i)
                    if (actualType != null) {
                        LOGGER.info("  -> 找到匹配: ${param.name} -> ${actualType.canonicalText}")
                        if (isByteBufType(actualType)) {
                            LOGGER.info("  -> 是 ByteBuf 类型")
                            return actualType
                        }
                        if (actualType is PsiClassType) {
                            val resolved = actualType.resolve()
                            if (resolved is PsiTypeParameter) {
                                LOGGER.info("  -> 实际类型是类型参数: ${resolved.name}")
                                return findTypeParameterBinding(resolved, context)
                            }
                        }
                    }
                }
            }
        }
    }

    // 3. 检查类的父类
    var superClass = context.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        LOGGER.info("  -> 检查父类: ${superClass.qualifiedName}")
        val superTypeParams = superClass.typeParameters
        for (param in superTypeParams) {
            if (param.name == typeParameter.name) {
                val superTypesOfContext = context.supers
                for (superType in superTypesOfContext) {
                    if (superType is PsiClassType && superType.resolve() == superClass) {
                        val params = superType.parameters
                        val index = superTypeParams.indexOf(param)
                        if (index >= 0 && index < params.size) {
                            val actualType = params[index]
                            LOGGER.info("  -> 在父类中找到绑定: ${param.name} -> ${actualType.canonicalText}")
                            if (isByteBufType(actualType)) {
                                LOGGER.info("  -> 是 ByteBuf 类型")
                                return actualType
                            }
                            if (actualType is PsiClassType) {
                                val resolved = actualType.resolve()
                                if (resolved is PsiTypeParameter) {
                                    return findTypeParameterBinding(resolved, context)
                                }
                            }
                        }
                    }
                }
            }
        }
        superClass = superClass.superClass
        depth++
    }

    LOGGER.info("  -> 未找到绑定")
    return null
}

/**
 * 判断类是否为 StreamCodec 类型
 */
private fun isStreamCodecType(psiClass: PsiClass): Boolean {
    val qualifiedName = psiClass.qualifiedName ?: return false
    val result = qualifiedName == "net.minecraft.network.codec.StreamCodec"
    LOGGER.fine("isStreamCodecType: $qualifiedName -> $result")
    return result
}

/**
 * 获取 ByteBuf 的 PsiType
 */
private fun getByteBufType(project: Project): PsiType {
    val byteBufClass = getByteBufPsiClass(project)
    return byteBufClass?.let {
        JavaPsiFacade.getElementFactory(project).createType(it)
    } ?: error("ByteBuf class not found")
}

/**
 * 获取 ByteBuf 的 PsiClass
 */
private fun getByteBufPsiClass(project: Project): PsiClass? {
    val psiFacade = JavaPsiFacade.getInstance(project)
    return psiFacade.findClass("io.netty.buffer.ByteBuf", GlobalSearchScope.allScope(project))
}
