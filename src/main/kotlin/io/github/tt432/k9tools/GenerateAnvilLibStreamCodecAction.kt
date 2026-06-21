package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiField
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author QiuShui1012
 */
@Suppress("ConstPropertyName", "LocalVariableName", "DuplicatedCode")
class GenerateAnvilLibStreamCodecAction : AnAction() {
    companion object {
        const val ByteBufCodecs: String = "net.minecraft.network.codec.ByteBufCodecs"
        const val StreamCodec = "net.minecraft.network.codec.StreamCodec"
        const val StreamCodecUtil = "dev.anvilcraft.lib.v2.codec.StreamCodecUtil"
        const val ByteBuf: String = "io.netty.buffer.ByteBuf"
    }

    private fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        if (vanillaStreamCodecClasses.contains(typeName)) {
            return "$ByteBufCodecs.${vanillaStreamCodecFieldName[vanillaStreamCodecClasses.indexOf(typeName)]}"
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "$ByteBufCodecs.${vanillaStreamCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
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

            if (psiClass.isEnum && !psiClass.fields.any { it.name == "STREAM_CODEC" }) {
                val factory = PsiElementFactory.getInstance(project)
                val codecField = factory.createFieldFromText(
                    "public static final $StreamCodec<$ByteBuf, ${psiClass.name}> STREAM_CODEC = $StreamCodecUtil.enumStreamCodec(${psiClass.name}.class);",
                    psiClass
                )

                val styleManager = JavaCodeStyleManager.getInstance(project)
                psiClass.add(styleManager.shortenClassReferences(codecField))
            } else {
                val className = psiClass.name!!

                var fields = psiClass.allFields

                if (fields.any { it.name == "STREAM_CODEC" }) return@runWriteCommandAction

                fields = fields.filter { !it.hasModifier(JvmModifier.STATIC) }.toTypedArray()

                serialize(fields, className, psiClass, project)
            }
        }
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
                "    ${getGetter(className, it, getFieldAndGetterMethod(psiClass))},\n"
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

private val vanillaKeywordCodec = listOf(
    "boolean",
    "byte",
    "short",
    "int",
    "long",
    "float",
    "double"
)
