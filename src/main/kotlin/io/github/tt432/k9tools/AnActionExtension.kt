package io.github.tt432.k9tools

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * @author TT432
 */

data class Result(val editor: Editor?, val psiClass: PsiClass?)

fun getFieldAndGetterMethod(psiClass: PsiClass): Map<PsiField, String?> {
    val isRecord = psiClass.isRecord
    return psiClass.allFields.associateWith { field ->
        if (isRecord) {
            // Record 的 getter 方法名与字段名相同
            field.name
        } else {
            findGetterMethodName(psiClass, field)
        }
    }
}

private fun findGetterMethodName(psiClass: PsiClass, field: PsiField): String? {
    // 1. 检查手动编写的 Getter 方法
    val possibleNames = getPossibleGetterNames(psiClass, field)
    val manualGetter = psiClass.allMethods.firstOrNull { method ->
        method.name in possibleNames
    }
    if (manualGetter != null) return manualGetter.name

    // 2. 检查 Lombok 注解
    if (hasLombokGetter(psiClass, field)) {
        // 返回预期的 Getter 名称
        return possibleNames.firstOrNull()
    }

    return null
}

private fun getPossibleGetterNames(psiClass: PsiClass, field: PsiField): List<String> {
    val fieldName = field.name
    val isBoolean = field.type.canonicalText == "boolean" || field.type.canonicalText == "Boolean" || field.type.canonicalText == "java.lang.Boolean"

    // 检查是否有 @Accessors 注解
    val accessors = psiClass.modifierList?.annotations?.firstOrNull {
        it.resolveAnnotationType()?.qualifiedName == "lombok.experimental.Accessors"
    }

    val names = mutableListOf<String>()

    if (accessors != null) {
        // 检查 fluent 模式
        val fluent = accessors.findAttributeValue("fluent") as? PsiLiteralExpression
        if (fluent?.value == true) {
            // fluent 模式：直接使用字段名
            names.add(fieldName)
        } else {
            // 检查 prefix 配置
            val prefixAttr = accessors.findAttributeValue("prefix")
            if (prefixAttr is PsiArrayInitializerMemberValue) {
                prefixAttr.initializers.forEach { init ->
                    if (init is PsiLiteralExpression) {
                        val prefix = init.value?.toString() ?: return@forEach
                        if (fieldName.startsWith(prefix)) {
                            val baseName = fieldName.removePrefix(prefix)
                            names.add(if (isBoolean) "is${baseName.replaceFirstChar { it.uppercase() }}"
                            else "get${baseName.replaceFirstChar { it.uppercase() }}")
                        }
                    }
                }
            }
        }
    }

    // 如果没有找到 Accessors 配置，使用标准命名
    if (names.isEmpty()) {
        val baseName = fieldName
        names.add(if (isBoolean) "is${baseName.replaceFirstChar { it.uppercase() }}"
        else "get${baseName.replaceFirstChar { it.uppercase() }}")
    }

    return names
}

private fun hasLombokGetter(psiClass: PsiClass, field: PsiField): Boolean {
    // 检查字段上的 @Getter
    if (field.modifierList?.annotations?.any {
            it.resolveAnnotationType()?.qualifiedName == "lombok.Getter"
        } == true) return true

    // 检查类上的 @Getter、@Data、@Value
    val classAnnotations = psiClass.modifierList?.annotations?.mapNotNull {
        it.resolveAnnotationType()?.qualifiedName
    } ?: emptyList()

    val lombokGetterAnnotations = listOf(
        "lombok.Getter",
        "lombok.Data",
        "lombok.Value"
    )

    if (classAnnotations.any { it in lombokGetterAnnotations }) {
        if (!field.hasModifierProperty(PsiModifier.STATIC) &&
            !field.hasModifierProperty(PsiModifier.FINAL)) {
            return true
        }
    }

    return false
}

fun getGetterName(className: String, field: PsiField, map: Map<PsiField, String?>): String {
    return if (map.containsKey(field) && map[field] != null) "$className::${map[field]}" else "o -> o.${field.name}"
}

fun getPsiClass(event: AnActionEvent): Result {
    val editor = event.getData(CommonDataKeys.EDITOR)
    val psiFile = event.getData(CommonDataKeys.PSI_FILE)

    if (editor == null || psiFile == null) return Result(null, null)

    val element = psiFile.findElementAt(editor.caretModel.offset)
    val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

    return Result(editor, psiClass)
}

fun getFieldGeneric(type: PsiTypeElement?): Array<PsiTypeElement?> {
    type ?: return arrayOfNulls(0)
    val ref = type.innermostComponentReferenceElement ?: return arrayOfNulls(0)
    val parameterList = ref.parameterList ?: return arrayOfNulls(0)
    return parameterList.typeParameterElements
}

fun getTypeName(field: PsiField): String {
    return getTypeName(field.typeElement)
    //val typeElement = field.typeElement ?: return ""
    //return getTypeName(typeElement)
}

fun getTypeName(element: PsiTypeElement?): String {
    val ref = element!!.innermostComponentReferenceElement ?: return element.text
    return ref.qualifiedName
}
