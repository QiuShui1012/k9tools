package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiField
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author TT432
 */
@Suppress("ConstPropertyName", "DuplicatedCode")
class GenerateAnvilLibMapCodecAction : AnAction() {
    companion object {
        private const val MapCodec: String = "com.mojang.serialization.MapCodec"
        private const val CodecUtil: String = "dev.anvilcraft.lib.v2.codec.CodecUtil"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event)

            if (editor == null || psiClass == null) return@runWriteCommandAction

            if (psiClass.isEnum) {
                val action = event.actionManager.getAction("Generate.AnvilLibCodec")
                event.actionManager.tryToExecute(action, null, null, null, true)
            } else {
                processRecordOrClass(psiClass, project)
            }

        }
    }

    @Suppress("UnstableApiUsage")
    private fun processRecordOrClass(psiClass: PsiClass, project: Project) {
        val fields = psiClass.allFields

        if (fields.any { it.name == "CODEC" }) return

        val fieldsStr = StringBuilder()
        val className = psiClass.name!!

        fields.filter { !it.hasModifier(JvmModifier.STATIC) }.forEach {
            fieldsStr.append(
                """
                    ${getCodecRef(it.typeElement)}
                    .${getFieldOf(it)}
                    .forGetter(${
                        getGetterName(
                            className,
                            it,
                            getFieldAndGetterMethod(psiClass)
                        )
                    }),
                    
                """.trimIndent()
            )
        }

        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $MapCodec<$className> CODEC = $CodecUtil.mapCodec(\n$fieldsStr$className::new\n);",
                    psiClass
                )
            )
        )
    }

    private fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        if (vanillaCodecClasses.contains(typeName)) {
            return "$MapCodec.${vanillaCodecFieldName[vanillaCodecClasses.indexOf(typeName)]}"
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "$MapCodec.${vanillaCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
        } else when (typeName) {
            "java.util.List" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "${getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))}\n.listOf()"
                }
            }

            "java.util.Map" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "$MapCodec.unboundedMap(${
                        getCodecRef(
                            fieldGeneric[0],
                            getTypeName(fieldGeneric[0])
                        )
                    }, ${getCodecRef(fieldGeneric[1], getTypeName(fieldGeneric[1]))})"
                }
            }

            "java.util.Optional" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                }
            }

            else -> {
                return "$typeName.CODEC"
            }
        }

        return ""
    }

    private fun getFieldOf(field: PsiField): String {
        field.annotations.forEach { it ->
            val name = it.qualifiedName ?: return@forEach
            val split = name.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            if (split.isNotEmpty() && split[split.size - 1] == "Nullable") {
                return "optionalFieldOf(\"${field.name}\", ${getDefaultValue(field)})"
            }
        }

        return when (getTypeName(field)) {
            "java.util.Optional" -> "optionalFieldOf(\"${field.name}\")"
            else -> "fieldOf(\"${field.name}\")"
        }
    }

    private fun getDefaultValue(field: PsiField): String {
        val typeName = getTypeName(field)

        return when (vanillaCodecClasses.indexOf(typeName) + vanillaKeywordCodec.indexOf(typeName) + 1) {
            0 -> "false"
            1 -> "(byte) 0"
            2 -> "(short) 0"
            3 -> "0"
            4 -> "0L"
            5 -> "0.0F"
            6 -> "0.0D"
            7 -> "\"\""
            else -> "null"
        }
    }
}

private val vanillaCodecClasses = listOf(
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

private val vanillaKeywordCodec = listOf(
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
