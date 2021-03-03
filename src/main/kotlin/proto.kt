import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Message
import java.io.*
import java.nio.file.Paths

/**
 * See https://developers.google.com/protocol-buffers/docs/reference/proto3-spec
 */
fun dumpProtoFileDescriptorSet(
    protos: DescriptorProtos.FileDescriptorSet,
    destFolder: File,
    cleanDestFolder: Boolean = false
): List<File> {
    if(destFolder.isFile) throw IllegalArgumentException("destFolder was a file")
    if(destFolder.isDirectory && cleanDestFolder) {
        destFolder.listFiles()?.forEach {
            if(!it.deleteRecursively()) throw IllegalStateException("unable to clean destFolder")
        } ?: throw IllegalStateException("unable to clean destFolder")
    }

    val results = mutableListOf<File>()
    protos.fileList.forEach { proto ->
        val protoPath = Paths.get(destFolder.absolutePath, *proto.name.split("/").toTypedArray())
        protoPath.toFile()
            .apply {
                parentFile.mkdirs() // make sure file's home exists
                createNewFile()
                results.add(this)
            }
            .outputStream().let { IndentedWriter(OutputStreamWriter(it)) }
            .use { os ->

            val syntax = if(proto.hasSyntax()) {
                os.appendLine("syntax = \"${proto.syntax}\";")
                os.appendLine()
                when(proto.syntax) {
                    "proto3" -> 3
                    "proto2" -> 2
                    else -> throw IllegalStateException("invalid proto syntax version ${proto.syntax}")
                }
            } else 3

            val parsingContext = ParsingContext(os, syntax, proto.extensionList)

            if(proto.hasPackage()) {
                os.appendLine("package ${proto.`package`};")
                os.appendLine()
            }

            proto.dependencyList.forEach {
                os.appendLine("import \"${it}\";")
            }
            if(proto.dependencyList.size > 0) os.appendLine()


            if(proto.hasOptions()) {
                handleOptions(parsingContext, proto.options)
                os.appendLine()
            }

            proto.extensionList.groupBy { it.extendee }.forEach { extensionType ->
                handleExtensionType(parsingContext, extensionType.toPair())
                os.appendLine()
            }

            proto.messageTypeList.forEach { messageType ->
                handleMessageType(parsingContext, messageType)
                os.appendLine()
            }

            proto.enumTypeList.forEach { enumType ->
                handleEnumType(parsingContext, enumType)
                os.appendLine()
            }

            proto.serviceList.forEach { service ->
                handleService(parsingContext, service)
                os.appendLine()
            }

            os.flush()
        }
    }

    return results
}

/**
 * Any data needed in order to parse the binary representation.
 */
private data class ParsingContext(
    val os: IndentedWriter,
    val protoVersion: Int,
    val extensions: List<DescriptorProtos.FieldDescriptorProto> = emptyList()
)

/**
 * Wrapper around Writer class that can keep track of and add indents to all appended messages.
 */
private class IndentedWriter(private val base : Writer) : Closeable by base, Flushable by base, Appendable by base {
    /**
     * The active indent.
     */
    var indent: Int = 0
        set(value) {
            field = if(value < 0) 0 else value
        }

    /**
     * Calls the specified function [block] with the indent incremented, and decrements when leaving the block.
     */
    fun <T> indent(block: () -> T) {
        indent += 1
        try {
            block.invoke()
        } finally {
            indent -= 1
        }
    }

    /**
     * Calls the specified function [block] with the indent set to zero, and restore to the original indent when leaving the block.
     */
    fun <T> disableIndent(block: () -> T) {
        val originalIndent = indent
        try { run {}
            indent = 0
            block.invoke()
        } finally {
            indent = originalIndent
        }
    }

    override fun append(csq: CharSequence?): java.lang.Appendable {
        base.append("  ".repeat(indent))
        base.append(csq)
        return this
    }
}

private fun handleMessageType(ctx: ParsingContext, messageType: DescriptorProtos.DescriptorProto) {
    ctx.os.appendLine("message ${messageType.name} {")
    ctx.os.indent {
        if(messageType.hasOptions()) {
            handleOptions(ctx, messageType.options)
            ctx.os.appendLine()
        }

        messageType.enumTypeList.forEach { enumType ->
            handleEnumType(ctx, enumType)
        }

        messageType.nestedTypeList.filter { !it.options.mapEntry }.forEach { nestedType ->
            handleMessageType(ctx, nestedType)
        }

        messageType.oneofDeclList.forEachIndexed { index, oneOfField ->
            val oneOfFieldEntries = messageType.fieldList.filter { field -> field.hasOneofIndex() && field.oneofIndex == index }
            handleOneOf(ctx, oneOfField, oneOfFieldEntries)
        }

        messageType.fieldList.filter { field -> !field.hasOneofIndex() }.forEach { field ->
            val mapEntryMatch = messageType.nestedTypeList
                .filter { it.options.mapEntry }
                .firstOrNull { field.typeName.endsWith(".${messageType.name}.${it.name}") }

            if(mapEntryMatch != null) {
                handleMapField(ctx, field, mapEntryMatch)
            } else {
                handleField(ctx, field)
            }
        }

        if(messageType.reservedNameCount > 0) {
            handleReservedNames(ctx, messageType.reservedNameList)
        }

        if(messageType.reservedRangeCount > 0) {
            handleReservedRange(ctx, messageType.reservedRangeList)
        }
    }
    ctx.os.appendLine("}")
}

private fun handleEnumType(ctx: ParsingContext, enum: DescriptorProtos.EnumDescriptorProto) {
    ctx.os.appendLine("enum ${enum.name} {")
    ctx.os.indent {
        if (enum.hasOptions()) {
            handleOptions(ctx, enum.options)
        }
        enum.valueList.forEach { enumEntry ->
            ctx.os.appendLine("${enumEntry.name} = ${enumEntry.number};")
        }
    }
    ctx.os.appendLine("}")
}

private fun handleOneOf(ctx: ParsingContext, oneof: DescriptorProtos.OneofDescriptorProto,
                        oneOfFieldEntries: List<DescriptorProtos.FieldDescriptorProto>) {
    ctx.os.appendLine("oneof ${oneof.name} {")
    ctx.os.indent {
        if (oneof.hasOptions()) {
            handleOptions(ctx, oneof.options)
        }
        oneOfFieldEntries.forEach { field ->
            handleField(ctx, field)
        }
    }
    ctx.os.appendLine("}")
}

private fun handleExtensionType(ctx: ParsingContext, extensionType: Pair<String, List<DescriptorProtos.FieldDescriptorProto>>) {
    ctx.os.appendLine("extend ${extensionType.first} {")
    ctx.os.indent {
        extensionType.second.forEach { field ->
            handleField(ctx, field)
        }
    }
    ctx.os.appendLine("}")
}

private fun handleMapField(ctx: ParsingContext, field: DescriptorProtos.FieldDescriptorProto,
                           mapEntryMessageType: DescriptorProtos.DescriptorProto) {
    // "map" "<" keyType "," type ">" mapName "=" fieldNumber [ "[" fieldOptions "]" ] ";"
    val keyType = mapEntryMessageType.fieldList.first { it.name == "key" && it.number == 1 }
    val valueType = mapEntryMessageType.fieldList.first { it.name == "value" && it.number == 2 }
    ctx.os.append("map<${keyType.typeString},${valueType.typeString}> ${field.name} = ${field.number}")

    ctx.os.disableIndent {
        if (field.hasOptions()) {
            handleOptions(ctx, field.options)
        }
        ctx.os.appendLine(";")
    }
}

private fun handleField(ctx: ParsingContext, field: DescriptorProtos.FieldDescriptorProto) {
    // {REPEAT?} {TYPE} {NAME} = {NUMBER} [FIELD_OPTIONS,..n];
    ctx.os.append(field.label.asProtoString(ctx.protoVersion))
    ctx.os.disableIndent {
        ctx.os.append(" ${field.typeString} ${field.name} = ${field.number}")
        if (field.hasOptions()) {
            handleOptions(ctx, field.options)
        }
        ctx.os.appendLine(";")
    }
}

private fun handleService(ctx: ParsingContext, service: DescriptorProtos.ServiceDescriptorProto) {
    ctx.os.appendLine("service ${service.name} {")
    ctx.os.indent {
        service.methodList.forEach { method ->
            handleMethod(ctx, method)
        }
    }
    ctx.os.appendLine("}")
}

private fun handleMethod(ctx: ParsingContext, method: DescriptorProtos.MethodDescriptorProto) {
    ctx.os.appendLine(StringBuilder().apply {
        val cstream = if(method.clientStreaming) "stream " else ""
        val sstream = if(method.serverStreaming) "stream " else ""
        append("rpc ${method.name} ( ${cstream}${method.inputType} ) returns ( ${sstream}${method.outputType} )")
        if(method.hasOptions()) append(" {") else append(";")
    })

    if(method.hasOptions()) {
        ctx.os.indent {
            handleOptions(ctx, method.options)
        }
        ctx.os.appendLine("}")
    }
}

private fun handleOptions(ctx: ParsingContext, options:  DescriptorProtos.FieldOptions) {
    val fieldOptions = options.allFields
        .filter { (t, _) -> options.hasField(t) }
        .map { (t, u) ->
            "${t.name}=${ if(u is String) "\"${u}\"" else u }"
        }
        .joinToString(", ")

    ctx.os.append(" [${fieldOptions}]")
}

private fun handleOptions(ctx: ParsingContext, options: Message) {
    options.allFields
        .filter { (t, _) -> options.hasField(t)}
        .map { (t, u) -> "option ${t.name} = ${ if(u is String) "\"${u}\"" else u };" }
        .forEach { line -> ctx.os.appendLine(line) }
}

private fun handleReservedNames(ctx: ParsingContext, reservedNames: List<String>) {
    val reservedNameStr = reservedNames.joinToString(", ") { "\"$it\"" }
    ctx.os.appendLine("reserved $reservedNameStr;")
}

private fun handleReservedRange(ctx: ParsingContext, reservedRange:  List<DescriptorProtos.DescriptorProto.ReservedRange>) {
    val sorted = reservedRange.sortedBy { it.start }
    val reservedRangeStr = sorted.joinToString(", ") {
        if(it.start == it.end - 1) {
            "${it.start}"
        } else {
            "${it.start} to ${it.end - 1}"
        }
    }
    ctx.os.appendLine("reserved $reservedRangeStr;")
}

private val DescriptorProtos.FieldDescriptorProto.typeString : String
    get() = when (type) {
        DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE,
        DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM -> typeName
        else -> type.name.split("_")[1].toLowerCase()
    }

private fun DescriptorProtos.FieldDescriptorProto.Label.asProtoString(version: Int) =
    when(this) {
        DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL -> if(version == 3) "" else "optional"
        DescriptorProtos.FieldDescriptorProto.Label.LABEL_REQUIRED -> if(version == 3) "" else "required"
        DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED -> "repeated"
        else -> throw IllegalArgumentException("unsupported Label type ${this.name}")
    }

