package riscv.cache

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ICacheSpec extends AnyFlatSpec with ChiselScalatestTester {
  "ICache" should "handle miss, refill 4 words, and then hit" in {
    test(new ICache) { dut =>
      // --- Helper Variables for AXI Channels (Read Only for ICache) ---
      val axi_ar = dut.io.axi.read_address_channel
      val axi_r  = dut.io.axi.read_data_channel

      // Initialize AXI signals
      axi_ar.ARREADY.poke(false.B)
      axi_r.RVALID.poke(false.B)
      dut.io.cpu_req.poke(false.B)

      // Request an address (Miss) ---
      dut.io.cpu_addr.poke("h0000_1000".U)
      dut.io.cpu_req.poke(true.B)
      dut.clock.step() // Let SyncReadMem read

      // At this point, a Miss should occur: stall goes high, ARVALID goes high
      dut.io.cpu_stall.expect(true.B)
      axi_ar.ARVALID.expect(true.B)
      axi_ar.ARADDR.expect("h0000_1000".U)

      // Simulate AXI 4-word Refill ---
      for (i <- 0 until 4) {
        // AR Handshake
        axi_ar.ARREADY.poke(true.B)
        
        // Wait for master to assert ARVALID (if not already asserted)
        while (axi_ar.ARVALID.peekBoolean() == false) dut.clock.step()
        
        dut.clock.step()
        axi_ar.ARREADY.poke(false.B)

        // Return Data (R Channel)
        axi_r.RDATA.poke((0x100 + i).U) // Simulate instruction data 0x100, 0x101...
        axi_r.RVALID.poke(true.B)
        
        // Wait for master to be ready to accept data
        while (axi_r.RREADY.peekBoolean() == false) dut.clock.step()
        
        dut.clock.step()
        axi_r.RVALID.poke(false.B)
      }

      // Update Tag and return to Idle ---
      dut.clock.step(2) 
      dut.io.cpu_stall.expect(false.B) // Refill complete, Stall should be de-asserted

      // Request the same address again (Hit) ---
      dut.io.cpu_addr.poke("h0000_1000".U)
      dut.clock.step()
      
      dut.io.cpu_stall.expect(false.B)
      dut.io.cpu_data.expect(0x100.U) // Should read the first word refilled earlier
    }
  }
}
