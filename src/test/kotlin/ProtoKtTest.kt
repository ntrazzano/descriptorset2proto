import com.google.protobuf.DescriptorProtos
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStreamReader
import java.net.URL

internal class ProtoKtTest {
    @Test
    fun dumpProtoFileDescriptorSet(@TempDir tempDir: File) {
        val descriptorSet = fileFromClasspath("descriptor_set.desc")
            .openStream().use { DescriptorProtos.FileDescriptorSet.parseFrom(it) }

        val dumpedProtos = dumpProtoFileDescriptorSet(descriptorSet, tempDir, false)

        // loop over every file created, compare with expected value
        dumpedProtos.forEach { generatedFile ->
            val expected = scrubbedFileLines(File(fileFromClasspath("expected/${generatedFile.name}").file))
            val actual = scrubbedFileLines(generatedFile)
            assertLinesMatch(expected, actual, "dumped proto should match expected proto for '${generatedFile.name}'")
        }
    }

    /**
     * Slurp a file into memory, remove blank lines and trim any extra whitespace.
     */
    private fun scrubbedFileLines(file: File) =
        InputStreamReader(file.inputStream()).use { ins ->
            ins.readLines().filter { it.isNotBlank() }.map { it.trim() }
        }


    /**
     * Grab a file from the classpath.
     */
    private fun fileFromClasspath(name: String): URL {
        return ProtoKtTest::class.java.classLoader.getResource(name)
            ?: throw IllegalStateException("unable to find '$name' on classpath")
    }
}
