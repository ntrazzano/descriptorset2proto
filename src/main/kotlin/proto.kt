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

            if(proto.hasPackage()) {
                os.appendLine("package ${proto.`package`};")
                os.appendLine()
            }

            if(proto.hasOptions()) {
                handleOptions(os, proto.options)
                os.appendLine()
            }

            proto.dependencyList.forEach {
                os.appendLine("import \"${it}\";")
            }
            if(proto.dependencyList.size > 0) os.appendLine()

            proto.messageTypeList.forEach { messageType ->
                handleMessageType(os, messageType, syntax)
                os.appendLine()
            }

            proto.enumTypeList.forEach { enumType ->
                handleEnumType(os, enumType)
                os.appendLine()
            }

            proto.serviceList.forEach { service ->
                handleService(os, service)
                os.appendLine()
            }

            os.flush()
        }
    }

    return results
}

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

private fun handleMessageType(os: IndentedWriter, messageType: DescriptorProtos.DescriptorProto, protoVersion: Int) {
    os.appendLine("message ${messageType.name} {")
    os.indent {
        if(messageType.hasOptions()) {
            handleOptions(os, messageType.options)
            os.appendLine()
        }

        messageType.enumTypeList.forEach { enumType ->
            handleEnumType( os, enumType)
        }

        messageType.nestedTypeList.filter { !it.options.mapEntry }.forEach { nestedType ->
            handleMessageType(os, nestedType, protoVersion)
        }

        messageType.oneofDeclList.forEachIndexed { index, oneOfField ->
            val oneOfFieldEntries = messageType.fieldList.filter { field -> field.hasOneofIndex() && field.oneofIndex == index }
            handleOneOf(os, oneOfField, oneOfFieldEntries, protoVersion)
        }

        messageType.fieldList.filter { field -> !field.hasOneofIndex() }.forEach { field ->
            val mapEntryMatch = messageType.nestedTypeList
                .filter { it.options.mapEntry }
                .firstOrNull { field.typeName.endsWith(".${messageType.name}.${it.name}") }

            if(mapEntryMatch != null) {
                handleMapField(os, field, mapEntryMatch)
            } else {
                handleField(os, field, protoVersion)
            }
        }

        if(messageType.reservedNameCount > 0) {
            handleReservedNames(os, messageType.reservedNameList)
        }

        if(messageType.reservedRangeCount > 0) {
            handleReservedRange(os, messageType.reservedRangeList)
        }
    }
    os.appendLine("}")
}

private fun handleEnumType(os: IndentedWriter, enum: DescriptorProtos.EnumDescriptorProto) {
    os.appendLine("enum ${enum.name} {")
    os.indent {
        if (enum.hasOptions()) {
            handleOptions(os, enum.options)
        }
        enum.valueList.forEach { enumEntry ->
            os.appendLine("${enumEntry.name} = ${enumEntry.number};")
        }
    }
    os.appendLine("}")
}

private fun handleOneOf(os: IndentedWriter, oneof: DescriptorProtos.OneofDescriptorProto,
                        oneOfFieldEntries: List<DescriptorProtos.FieldDescriptorProto>, protoVersion: Int) {
    os.appendLine("oneof ${oneof.name} {")
    os.indent {
        if (oneof.hasOptions()) {
            handleOptions(os, oneof.options)
        }
        oneOfFieldEntries.forEach { field ->
            handleField(os, field, protoVersion)
        }
    }
    os.appendLine("}")
}

private fun handleMapField(os: IndentedWriter, field: DescriptorProtos.FieldDescriptorProto,
                           mapEntryMessageType: DescriptorProtos.DescriptorProto) {
    // "map" "<" keyType "," type ">" mapName "=" fieldNumber [ "[" fieldOptions "]" ] ";"
    val keyType = mapEntryMessageType.fieldList.first { it.name == "key" && it.number == 1 }
    val valueType = mapEntryMessageType.fieldList.first { it.name == "value" && it.number == 2 }
    os.append("map<${keyType.typeString},${valueType.typeString}> ${field.name} = ${field.number}")

    os.disableIndent {
        if (field.hasOptions()) {
            handleOptions(os, field.options)
        }
        os.appendLine(";")
    }
}

private fun handleField(os: IndentedWriter, field: DescriptorProtos.FieldDescriptorProto, protoVersion: Int) {
    // {REPEAT?} {TYPE} {NAME} = {NUMBER} [FIELD_OPTIONS,..n];
    os.append(field.label.asProtoString(protoVersion).let { if(it.isNotEmpty()) "$it " else it })
    os.disableIndent {
        os.append("${field.typeString} ${field.name} = ${field.number}")
        if (field.hasOptions()) {
            handleOptions(os, field.options)
        }
        os.appendLine(";")
    }
}

private fun handleService(os: IndentedWriter, service: DescriptorProtos.ServiceDescriptorProto) {
    os.appendLine("service ${service.name} {")
    os.indent {
        service.methodList.forEach { method ->
            handleMethod(os, method)
        }
    }
    os.appendLine("}")
}

private fun handleMethod(os: IndentedWriter, method: DescriptorProtos.MethodDescriptorProto) {
    os.appendLine("rpc ${method.name}" +
            " ( ${if(method.clientStreaming) "stream " else ""}${method.inputType} )" +
            " returns ( ${if(method.serverStreaming) "stream " else ""}${method.outputType} );")
}

private fun handleOptions(os: IndentedWriter, options:  DescriptorProtos.FieldOptions) {
    val fieldOptions = options.allFields
        .filter { (t, _) -> options.hasField(t) }
        .map { (t, u) -> "${t.name}=${if(u is Boolean){u}else{"\"${u}\""}}" }
        .joinToString { "," }

    os.append(" [${fieldOptions}]")
}

private fun handleOptions(os: IndentedWriter, options: Message) {
    options.allFields
        .filter { (t, _) -> options.hasField(t) }
        .map { (t, u) -> "option ${t.name} = ${if(u is Boolean) { u } else { "\"${u}\"" }};" }
        .forEach { line -> os.appendLine(line) }
}

private fun handleReservedNames(os: IndentedWriter, reservedNames: List<String>) {
    val reservedNameStr = reservedNames.joinToString(", ") { "\"$it\"" }
    os.appendLine("reserved $reservedNameStr;")
}

private fun handleReservedRange(os: IndentedWriter, reservedRange:  List<DescriptorProtos.DescriptorProto.ReservedRange>) {
    val sorted = reservedRange.sortedBy { it.start }
    val reservedRangeStr = sorted.joinToString(", ") {
        if(it.start == it.end - 1) {
            "${it.start}"
        } else {
            "${it.start} to ${it.end - 1}"
        }
    }
    os.appendLine("reserved $reservedRangeStr;")
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

