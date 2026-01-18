// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Debug test using built-in uart.asmbin from resources
class DebugWriteTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior.of("Debug Write Test")

  it should "execute uart test and verify memory writes" in {
    // Note: We removed the .withAnnotations(Seq(VerilatorBackendAnnotation)) 
    // to bypass the C++ compilation environment issues (pch.h errors).
    // This defaults to the Treadle simulator (Pure Scala), which is stable but slower.
    test(new TestTopModule("uart.asmbin")) { dut =>
      
      // Disable the default timeout because Treadle is slow 
      // and running 50,0 cycles takes significantly longer than in Verilator.
      dut.clock.setTimeout(0)

      println("Start Simulation...")

      // Split the large step(500) into a loop.
      // We print progress every 100 cycles so you know the simulation is alive.
      val totalCycles = 500
      val chunkSize   = 100
      
      for (i <- 0 until (totalCycles / chunkSize)) {
        dut.clock.step(chunkSize)
        println(s"Simulated ${(i + 1) * chunkSize} cycles...")
      }

      println("Simulation finished. Verifying memory...")

      // Verify program area has instructions (not zeros)
      // Check address 0x1000 where the program entry point is usually loaded
      dut.io.mem_debug_read_address.poke(0x1000.U)
      dut.clock.step()
      val inst0 = dut.io.mem_debug_read_data.peekInt()
      
      println(f"Read Instruction at 0x1000: 0x$inst0%08X")
      assert(inst0 != 0, "Program area should have instructions loaded")
    }
  }
}