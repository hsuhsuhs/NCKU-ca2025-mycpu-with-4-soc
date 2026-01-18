package riscv.cache

import bus.AXI4LiteSlave
import chisel3._
import chiseltest._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import peripheral.Memory

class DCacheWVcdTest2 extends AnyFlatSpec with ChiselScalatestTester {
  behavior.of("DCache WSTRB Full Coverage Verification (VCD)")

  it should "generate correct WSTRB for all Store permutations" in {
    test(new Module {
      val io = IO(new Bundle {
        // CPU side
        val cpu_req   = Input(Bool())
        val cpu_addr  = Input(UInt(32.W))
        val cpu_we    = Input(Bool())
        val cpu_wdata = Input(UInt(32.W))
        val cpu_func3 = Input(UInt(3.W))
        
        val cpu_stall = Output(Bool())
        val cpu_data  = Output(UInt(32.W)) 

        // Debug signals
        val dbg_wstrb   = Output(UInt(4.W)) 
        val dbg_awvalid = Output(Bool())
      })

      val dcache = Module(new DCache)
      val mem    = Module(new Memory(1024))
      val slave  = Module(new AXI4LiteSlave(32, 32))

      // ---------------- Connections ----------------
      // CPU <-> DCache
      dcache.io.cpu_req   := io.cpu_req
      dcache.io.cpu_addr  := io.cpu_addr
      dcache.io.cpu_we    := io.cpu_we
      dcache.io.cpu_wdata := io.cpu_wdata
      dcache.io.cpu_func3 := io.cpu_func3
      io.cpu_stall        := dcache.io.cpu_stall
      io.cpu_data         := dcache.io.cpu_data

      // DCache Master <-> AXI Slave (Bulk Connection)
      slave.io.channels <> dcache.io.axi

      // Slave <-> Memory
      // Need to handle address muxing for the simple Memory module
      // Create aliases for long nested names to make code readable
      val axi_aw = dcache.io.axi.write_address_channel
      val axi_ar = dcache.io.axi.read_address_channel
      val axi_w  = dcache.io.axi.write_data_channel

      val latchedWriteAddr = RegEnable(axi_aw.AWADDR, axi_aw.AWVALID && axi_aw.AWREADY)
      
      mem.io.bundle.address := Mux(slave.io.bundle.write, 
                                   latchedWriteAddr >> 2, 
                                   axi_ar.ARADDR >> 2)
      
      mem.io.bundle.write_enable := slave.io.bundle.write
      mem.io.bundle.write_data   := slave.io.bundle.write_data
      mem.io.bundle.write_strobe := slave.io.bundle.write_strobe
      
      slave.io.bundle.read_data  := mem.io.bundle.read_data
      // Simulate 1 cycle latency for read valid
      slave.io.bundle.read_valid := RegNext(axi_ar.ARVALID)

      mem.io.instruction_address := 0.U
      mem.io.debug_read_address  := 0.U

      // Debug Outputs
      io.dbg_wstrb   := axi_w.WSTRB
      io.dbg_awvalid := axi_aw.AWVALID

    }).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(5000)
      val baseAddr = 0x200

      // Step 0: Initialize
      dut.io.cpu_req.poke(false.B)
      dut.io.cpu_we.poke(false.B)
      dut.clock.step(5)
      
      // ==========================================
      // Test Loop
      
      val testCases = Seq(
        ("sb offset 0", 0x0, 0, 1),  // 0001
        ("sb offset 1", 0x0, 1, 2),  // 0010
        ("sb offset 2", 0x0, 2, 4),  // 0100
        ("sb offset 3", 0x0, 3, 8),  // 1000
        ("sh offset 0", 0x1, 0, 3),  // 0011
        ("sh offset 2", 0x1, 2, 12), // 1100
        ("sw offset 0", 0x2, 0, 15)  // 1111
      )

      for ((name, func3, offset, expected) <- testCases) {
        println(f"=== Testing $name: func3=$func3, offset=$offset, expect WSTRB=0x$expected%X ===")
        
        // Setup Inputs
        dut.io.cpu_addr.poke((baseAddr + offset).U)
        dut.io.cpu_wdata.poke("hFFFFFFFF".U)
        dut.io.cpu_we.poke(true.B)
        dut.io.cpu_func3.poke(func3.U)
        dut.io.cpu_req.poke(true.B)

        dut.clock.step() 

        // Check WSTRB
        val wstrb = dut.io.dbg_wstrb.peekInt()
        
        // Use toBinaryString instead of toString(2) for safety
        val binaryStr   = String.format("%4s", wstrb.toString(2)).replace(' ', '0')
        val expectedStr = String.format("%4s", expected.toBinaryString).replace(' ', '0')
        
        println(s"  -> Got: b$binaryStr, Expected: b$expectedStr")
        assert(wstrb == expected, s"Failed $name! Expected b$expectedStr but got b$binaryStr")

        // Wait for Stall
        var cycles = 0
        while (dut.io.cpu_stall.peekBoolean() && cycles < 100) { 
          dut.clock.step()
          cycles += 1 
        }

        dut.io.cpu_req.poke(false.B)
        dut.io.cpu_we.poke(false.B)
        dut.clock.step(2)
      }

    }
  }
}