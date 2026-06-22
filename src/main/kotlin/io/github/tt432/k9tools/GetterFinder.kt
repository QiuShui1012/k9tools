package io.github.tt432.k9tools

import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import java.util.logging.Logger

/**
 * @author QiuShui1012 & TT432
 */

private val LOGGER = Logger.getLogger("GetterFinder")

fun getFieldAndGetterMethod(psiClass: PsiClass): Map<PsiField, String?> {
    LOGGER.info("=== getFieldAndGetterMethod 开始 ===")
    LOGGER.info("类: ${psiClass.qualifiedName}, isRecord=${psiClass.isRecord}")

    val isRecord = psiClass.isRecord
    val result = psiClass.allFields.associateWith { field ->
        if (isRecord) {
            LOGGER.info("  Record 字段 ${field.name} -> getter 名: ${field.name}")
            field.name
        } else {
            val getterName = findGetterMethodName(psiClass, field)
            LOGGER.info("  字段 ${field.name} (类型: ${field.type.canonicalText}) -> getter 名: $getterName")
            getterName
        }
    }

    LOGGER.info("=== getFieldAndGetterMethod 结束，找到 ${result.size} 个字段 ===")
    return result
}

private fun findGetterMethodName(psiClass: PsiClass, field: PsiField): String? {
    LOGGER.fine("findGetterMethodName: 类=${psiClass.qualifiedName}, 字段=${field.name}")

    // 1. 检查手动编写的 Getter 方法
    val possibleNames = getPossibleGetterNames(psiClass, field)
    LOGGER.fine("  可能的 getter 名称: $possibleNames")

    val result = possibleNames.firstOrNull { name ->
        val methods = psiClass.findMethodsByName(name, false)
        val found = methods.any { method ->
            val returnType = method.returnTypeElement?.type
            val matches = returnType?.equals(field.type) == true
            if (matches) {
                LOGGER.fine("    找到匹配的 getter: $name, 返回类型: ${returnType.canonicalText}")
            }
            matches
        }
        if (found) {
            LOGGER.fine("    使用 getter: $name")
        }
        found
    }

    if (result == null) {
        LOGGER.fine("  未找到 getter 方法")
    }

    return result
}

private fun getPossibleGetterNames(psiClass: PsiClass, field: PsiField): List<String> {
    val fieldName = field.name
    val fieldType = field.type.canonicalText
    val isBoolean = fieldType == "boolean" || fieldType == "Boolean" || fieldType == "java.lang.Boolean"

    LOGGER.fine("getPossibleGetterNames: 字段=$fieldName, 类型=$fieldType, isBoolean=$isBoolean")

    // 检查是否有 @Accessors 注解
    val accessors = psiClass.modifierList?.annotations?.firstOrNull {
        it.resolveAnnotationType()?.qualifiedName == "lombok.experimental.Accessors"
    }

    val names = mutableListOf<String>()

    if (accessors != null) {
        LOGGER.fine("  找到 @Accessors 注解")
        // 检查 fluent 模式
        val fluent = accessors.findAttributeValue("fluent") as? PsiLiteralExpression
        if (fluent?.value == true) {
            LOGGER.fine("  fluent 模式: 使用字段名 '$fieldName'")
            names.add(fieldName)
        } else {
            // 检查 prefix 配置
            val prefixAttr = accessors.findAttributeValue("prefix")
            if (prefixAttr is PsiArrayInitializerMemberValue) {
                LOGGER.fine("  prefix 配置数量: ${prefixAttr.initializers.size}")
                prefixAttr.initializers.forEach { init ->
                    if (init is PsiLiteralExpression) {
                        val prefix = init.value?.toString() ?: return@forEach
                        if (fieldName.startsWith(prefix)) {
                            val baseName = fieldName.removePrefix(prefix)
                            val getterName = if (isBoolean) "is${baseName.replaceFirstChar { it.uppercase() }}"
                            else "get${baseName.replaceFirstChar { it.uppercase() }}"
                            LOGGER.fine("    prefix '$prefix' 匹配: $fieldName -> $getterName")
                            names.add(getterName)
                        }
                    }
                }
            } else {
                LOGGER.fine("  @Accessors 没有 prefix 配置或不是数组")
            }
        }
    } else {
        LOGGER.fine("  未找到 @Accessors 注解，使用标准命名")
    }

    // 如果没有找到 Accessors 配置，使用标准命名
    if (names.isEmpty()) {
        val baseName = fieldName
        val getterName = if (isBoolean) "is${baseName.replaceFirstChar { it.uppercase() }}"
        else "get${baseName.replaceFirstChar { it.uppercase() }}"
        LOGGER.fine("  标准命名: $getterName")
        names.add(getterName)
    }

    LOGGER.fine("  最终可能的 getter 名称: $names")
    return names
}

fun getGetter(className: String, field: PsiField, map: Map<PsiField, String?>): String {
    val result = if (map.containsKey(field) && map[field] != null) {
        "$className::${map[field]}"
    } else {
        "o -> o.${field.name}"
    }
    LOGGER.fine("getGetter: 类=$className, 字段=${field.name}, 结果=$result")
    return result
}
