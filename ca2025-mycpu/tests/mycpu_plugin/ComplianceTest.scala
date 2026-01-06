// SPDX-License-Identifier: MIT
// Shared RISCOF compliance test harness for all MyCPU projects

package riscv.compliance

import java.nio.file.{Files, Paths}
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Extract begin_signature and end_signature addresses from ELF file
 */
object ElfSignatureExtractor {
  def extractSignatureRange(elfFile: String): (BigInt, BigInt) = {
    // Try different RISC-V toolchain prefixes
    val prefixes = Seq("riscv32-unknown-elf", "riscv-none-elf", "riscv64-unknown-elf")
    val readelfCmd = prefixes.map(p => s"${p}-readelf").find { cmd =>
      try {
        scala.sys.process.Process(Seq(cmd, "--version")).! == 0
      } catch {
        case _: Exception => false
      }
    }.getOrElse("riscv-none-elf-readelf")

    val cmd = s"$readelfCmd -s $elfFile"
    val output = scala.sys.process.Process(cmd).!!

    var beginAddress: BigInt = BigInt(0)
    var endAddress: BigInt = BigInt(0)

    output.split("\n").foreach { line =>
      if (line.contains("begin_signature")) {
        val parts = line.trim.split("\\s+")
        if (parts.length > 1) {
          beginAddress = BigInt("0" + parts(1).substring(1), 16)
        }
      } else if (line.contains("end_signature")) {
        val parts = line.trim.split("\\s+")
        if (parts.length > 1) {
          endAddress = BigInt("0" + parts(1).substring(1), 16)
        }
      }
    }

    if (beginAddress == 0 || endAddress == 0) {
      throw new Exception("Failed to extract begin_signature or end_signature from ELF")
    }

    (beginAddress, endAddress)
  }
}

/**
 * Base compliance test class
 *
 * Usage: Extend this class and provide TestTopModule implementation
 * The TestTopModule must have:
 *   - io.mem_debug_read_address: Input(UInt(32.W))
 *   - io.mem_debug_read_data: Output(UInt(32.W))
 */
abstract class ComplianceTestBase extends AnyFlatSpec with ChiselScalatestTester {

  /**
   * Run compliance test
   *
   * @param dutModule The device under test module
   * @param elfFile Path to ELF file
   * @param sigFile Path to signature output file
   * @param annos ChiselTest annotations
   * @param maxCycles Maximum simulation cycles
   */
  def runComplianceTest[T <: Module](
    dutModule: => T,
    elfFile: String,
    sigFile: String,
    annos: Seq[chiseltest.internal.WriteVcdAnnotation | chiseltest.VerilatorBackendAnnotation],
    maxCycles: Int = 50000
  )(implicit evidence: T <:< {
    def io: {
      def mem_debug_read_address: UInt
      def mem_debug_read_data: UInt
    }
    def clock: Clock
  }): Unit = {

    // Extract signature range from ELF
    val (startAddress, endAddress) = ElfSignatureExtractor.extractSignatureRange(elfFile)

    test(dutModule).withAnnotations(annos) { dut =>
      // Run simulation
      dut.clock.setTimeout(0) // Disable timeout
      dut.clock.step(maxCycles)

      // Extract signature from memory
      val writer = new java.io.PrintWriter(sigFile)

      // Iterate through entire memory range (signature is offset by 0x1000)
      for (addr <- 0L to endAddress.toLong by 4L) {
        val readAddr = addr.toLong + 0x1000L // Entry address offset
        dut.io.mem_debug_read_address.poke(readAddr.U)
        dut.clock.step()
        val data = dut.io.mem_debug_read_data.peek().litValue

        // Only write signature range
        if (addr >= startAddress && addr < endAddress) {
          writer.printf("%08x\n", data.toLong)
        }
      }

      writer.close()
    }
  }
}
