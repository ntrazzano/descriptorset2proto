import com.google.protobuf.DescriptorProtos
import org.apache.commons.cli.*
import java.io.File

fun main(args: Array<String>) {
    val parser = DefaultParser()
    val options = buildOptions()
    val parsedOptions = try {
        validateArgs(parser.parse(options, args))
    } catch(e : ParseException) {
        System.err.println("${e.message}")
        options.printHelp(1)
    }

    try {
        val protos = readFileDescriptorSet(File(parsedOptions.getOptionValue('s')))
        dumpProtoFileDescriptorSet(
            protos,
            File(parsedOptions.getOptionValue('d')),
            parsedOptions.hasOption('c')
        )
    } catch (e: Exception) {
        System.err.println("failed to run, reason : ${e.message}")
        options.printHelp(2)
    }
}

fun Options.printHelp(exitCode : Int = 0): Nothing {
    HelpFormatter().printHelp("descriptorset2proto", this)
    kotlin.system.exitProcess(exitCode)
}

fun buildOptions() = Options().apply {
    addOption("s", "source", true, "source descriptor file, aka protoset")
    addOption("d", "destination", true, "destination folder to generate the protos")
    addOption("c", "clean", false, "clean destination folder before rebuild")
}

fun validateArgs(args: CommandLine): CommandLine {
    File(args.getOptionValue('s') ?: throw ParseException("source wasn't provided")).apply {
        exists() || throw IllegalStateException("source file provided does not exist")
        isFile || throw IllegalStateException("source file provided is a folder")
    }

    File(args.getOptionValue('d')  ?: throw ParseException("destination wasn't provided")).apply {
        if (exists() && isFile) throw IllegalStateException("destination folder provided is a file")
    }

    return args
}

fun readFileDescriptorSet(descriptorSetFile: File)  = descriptorSetFile.inputStream().use {
    DescriptorProtos.FileDescriptorSet.parseFrom(it)
}
