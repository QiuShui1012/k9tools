package io.github.tt432.k9tools

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * @author TT432
 */

data class Result(val editor: Editor?, val psiClass: PsiClass?)

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
