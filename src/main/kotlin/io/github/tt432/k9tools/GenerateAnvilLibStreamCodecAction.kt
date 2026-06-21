package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author TT432
 */
@Suppress("ConstPropertyName", "LocalVariableName", "DuplicatedCode")
class GenerateAnvilLibStreamCodecAction : AnAction() {
    companion object {
        const val ByteBufCodecs: String = "net.minecraft.network.codec.ByteBufCodecs"
        const val StreamCodec = "net.minecraft.network.codec.StreamCodec"
        const val StreamCodecUtil = "dev.anvilcraft.lib.v2.codec.StreamCodecUtil"
        const val Locale: String = "java.util.Locale"
        const val ByteBuf: String = "io.netty.buffer.ByteBuf"
    }

    private fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        if (vanillaCodecClasses.contains(typeName)) {
            return "$ByteBufCodecs.${vanillaCodecFieldName[vanillaCodecClasses.indexOf(typeName)]}"
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "$ByteBufCodecs.${vanillaCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
        } else when (typeName) {
            "java.util.List" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "${
                        getCodecRef(
                            fieldGeneric[0],
                            getTypeName(fieldGeneric[0])
                        )
                    }.apply($ByteBufCodecs.list())"
                }
            }

            "java.util.Map" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "$ByteBufCodecs.map(java.util.HashMap::new, ${
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
                    return "$ByteBufCodecs.optional(${getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))})"
                }
            }

            else -> {
                return "$typeName.STREAM_CODEC"
            }
        }

        return ""
    }

    @Suppress("UnstableApiUsage")
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event)

            if (editor == null || psiClass == null) return@runWriteCommandAction

            if (psiClass.isEnum) {
                addImplementsClause(psiClass, editor, project, "StringRepresentable")

                val factory = PsiElementFactory.getInstance(project)
                val styleManager = JavaCodeStyleManager.getInstance(project)

                if (!psiClass.fields.any { it.name == "STREAM_CODEC" }) {
                    val codecField = factory.createFieldFromText(
                        "public static final $StreamCodec<$ByteBuf, ${psiClass.name}> STREAM_CODEC = $StreamCodecUtil.enumStreamCodec(${psiClass.name}.class);",
                        psiClass
                    )

                    psiClass.add(styleManager.shortenClassReferences(codecField))
                }

                if (!psiClass.methods.any { it.name == "getSerializedName" }) {
                    val stringRepresentableImpl = factory.createMethodFromText(
                        "@Override public String getSerializedName() { return this.name().toLowerCase($Locale.ROOT); }",
                        psiClass
                    )

                    psiClass.add(styleManager.shortenClassReferences(stringRepresentableImpl))
                }
            } else {
                val className = psiClass.name!!

                var fields = psiClass.allFields

                if (fields.any { it.name == "STREAM_CODEC" }) return@runWriteCommandAction

                fields = fields.filter { !it.hasModifier(JvmModifier.STATIC) }.toTypedArray()

                serialize(fields, className, psiClass, project)
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

    private fun serialize(
        fields: Array<out PsiField>,
        className: @NlsSafe String,
        psiClass: PsiClass,
        project: Project
    ) {
        val fieldsStr = StringBuilder()

        fields.forEach {
            fieldsStr.append(
                "    ${getCodecRef(it.typeElement)},\n" +
                "    ${getGetterName(className, it, getFieldAndGetterMethod(psiClass))},\n"
            )
        }

        val ByteBuf = getFinalByteBufType(fields, project)
        val CompositeSource = if (fields.size <= 6) StreamCodec else StreamCodecUtil
        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $StreamCodec<$ByteBuf, $className> STREAM_CODEC = $CompositeSource.composite(\n$fieldsStr$className::new\n);",
                    psiClass
                )
            )
        )
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
    "net.minecraft.nbt.Tag",
    "net.minecraft.nbt.CompoundTag",
    "org.joml.Vector3f",
    "org.joml.Quaternionf",
    "com.mojang.authlib.properties.PropertyMap",
    "com.mojang.authlib.GameProfile",
    "byte[]"
)

private val vanillaCodecFieldName = listOf(
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
    "BYTE_ARRAY"
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
