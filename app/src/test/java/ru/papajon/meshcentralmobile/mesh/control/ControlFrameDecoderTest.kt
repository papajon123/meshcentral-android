package ru.papajon.meshcentralmobile.mesh.control

import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.management.RunCommand
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent
import ru.papajon.meshcentralmobile.mesh.MeshControlParser

class ControlFrameDecoderTest {
    @Test fun twentyMegabyteCommandOutputIsBoundedBeforeJsonParsingAndNeverARoutingMessage() {
        // Actual agent order: output precedes responseid; server appends nodeid.
        val text = """{"action":"msg","type":"runcommands","result":"""" + "x".repeat(20 * 1024 * 1024) +
            """","sessionid":"synthetic","responseid":"request","nodeid":"node//example"}"""
        val reply = requireNotNull(ControlFrameDecoder.decode(text))
        assertEquals(RunCommand.OUTPUT_LIMIT, reply.getString("result").length)
        assertEquals(RunCommand.OUTPUT_LIMIT, RunCommand.output(reply).length)
        assertTrue(RunCommand.outputTruncated(reply))
        assertEquals("request", reply.getString("responseid"))
        assertEquals("node//example", reply.getString("nodeid"))
        assertTrue(MeshControlParser.parse(reply) is MeshControlEvent.Unknown)
        assertFalse(reply.has("sessionid"))
    }
    @Test fun escapedLargeOutputAndReorderedMetadataRemainCorrelated() {
        val reply = requireNotNull(ControlFrameDecoder.decode("""{"result":"""" + "\\n\\u0410\\\"".repeat(50_000) +
            """","nodeid":"node//example","type":"runcommands","action":"msg","responseid":"request"}"""))
        assertTrue(reply.getString("result").startsWith("\nА\""))
        assertTrue(RunCommand.outputTruncated(reply))
        assertTrue(MeshControlParser.parse(reply) is MeshControlEvent.Unknown)
    }
    @Test fun smallOutputAlsoNeverBecomesRoutingFailureAndOtherFramesStayIntact() {
        assertTrue(MeshControlParser.parse("""{"action":"msg","type":"runcommands","result":"PARITY_OK","nodeid":"node//example"}""") is MeshControlEvent.Unknown)
        assertTrue(MeshControlParser.parse("""{"action":"msg","result":"Unable to route","responseid":"example"}""") is MeshControlEvent.RoutingResult)
        val text = """{"action":"software","value":"""" + "x".repeat(200_000) + "\"}"
        assertEquals(200_000, ControlFrameDecoder.decode(text)!!.getString("value").length)
    }
}
