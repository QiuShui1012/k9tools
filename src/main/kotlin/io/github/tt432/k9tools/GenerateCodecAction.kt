package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiField
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author TT432
 */
@Suppress("ConstPropertyName", "DuplicatedCode")
class GenerateCodecAction : AnAction() {
    companion object {
        private const val Codec: String = "com.mojang.serialization.Codec"
        private const val StringRepresentable: String = "net.minecraft.util.StringRepresentable"
        private const val NonNull: String = "org.jspecify.annotations.NonNull"
        private const val Locale: String = "java.util.Locale"
        private const val RecordCodecBuilder: String = "com.mojang.serialization.codecs.RecordCodecBuilder"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event)

            if (editor == null || psiClass == null) return@runWriteCommandAction

            if (psiClass.isEnum) {
                addImplementsClause(psiClass, editor, project, "StringRepresentable")

                val factory = PsiElementFactory.getInstance(project)
                val styleManager = JavaCodeStyleManager.getInstance(project)

                if (!psiClass.fields.any { it.name == "CODEC" }) {
                    val codecField = factory.createFieldFromText(
                        "public static final $Codec<${psiClass.name}> CODEC = $StringRepresentable.fromEnum(${psiClass.name}::values);",
                        psiClass
                    )

                    psiClass.add(styleManager.shortenClassReferences(codecField))
                }

                if (!psiClass.methods.any { it.name == "getSerializedName" }) {
                    val stringRepresentableImpl = factory.createMethodFromText(
                        "@Override @$NonNull public String getSerializedName() { return this.name().toLowerCase($Locale.ROOT); }",
                        psiClass
                    )

                    psiClass.add(styleManager.shortenClassReferences(stringRepresentableImpl))
                }
            } else {
                processRecordOrClass(psiClass, project)
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun addImplementsClause(psiClass: PsiClass, editor: Editor, project: Project, interfaceName: String) {
        val implementsList = psiClass.implementsList
        if (implementsList != null) {
            val existingInterfaces = implementsList.referenceElements.map { it.referenceName }
            if (existingInterfaces.contains(interfaceName)) {
                return // 已经实现了该接口，无需重复添加
            }

            // 在现有 implements 列表末尾追加新接口
            val lastInterface = implementsList.referenceElements.lastOrNull()
            if (lastInterface != null) {
                val insertOffset = lastInterface.textRange.endOffset
                editor.document.insertString(insertOffset, ", $interfaceName")
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
                return
            }
        }

        // 没有 implements 子句，在类名后添加
        val nameIdentifier = psiClass.nameIdentifier ?: return
        val insertOffset = nameIdentifier.textRange.endOffset
        editor.document.insertString(insertOffset, " implements $interfaceName")
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
    }

    @Suppress("UnstableApiUsage")
    private fun processRecordOrClass(psiClass: PsiClass, project: Project) {
        val fields = psiClass.allFields

        if (fields.any { it.name == "CODEC" }) return

        val fieldsStr = StringBuilder()
        val className = psiClass.name!!

        fields.filter { !it.hasModifier(JvmModifier.STATIC) }.forEach {
            fieldsStr.append(
                "    ${getCodecRef(it.typeElement)}.${getFieldOf(it)}.forGetter(${
                    getGetterName(
                        className,
                        it,
                        getFieldAndGetterMethod(psiClass)
                    )
                }),\n"
            )
        }

        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $Codec<$className> CODEC = $RecordCodecBuilder.create(ins -> ins.group(\n" +
                            "${fieldsStr.toString().removeSuffix(",\n") + "\n"}).apply(ins, $className::new));",
                    psiClass
                )
            )
        )
    }

    private fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        if (vanillaCodecClasses.contains(typeName)) {
            return "$Codec.${vanillaCodecFieldName[vanillaCodecClasses.indexOf(typeName)]}"
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "$Codec.${vanillaCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
        } else when (typeName) {
            "java.util.List" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "${getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))}.listOf()"
                }
            }

            "java.util.Map" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "$Codec.unboundedMap(${
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
