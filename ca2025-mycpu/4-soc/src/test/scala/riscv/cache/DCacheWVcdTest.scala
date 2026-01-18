package riscv.cache

import bus.AXI4LiteSlave
import chisel3._
import chiseltest._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import peripheral.Memory

class DCacheWVcdTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior.of("DCache with AXI4-Lite Memory System (VCD)")

  it should "handle Write-Through, Refill on Miss, Hit, and MMIO" in {
    test(new Module {
      val io = IO(new Bundle {
        // CPU side
        val cpu_req   = Input(Bool())
        val cpu_addr  = Input(UInt(32.W))
        val cpu_we    = Input(Bool())
        val cpu_wdata = Input(UInt(32.W))
        val cpu_func3 = Input(UInt(3.W))
        
        val cpu_data  = Output(UInt(32.W))
        val cpu_stall = Output(Bool())

        // Debug signals for VCD
        val dbg_awvalid = Output(Bool())
        val dbg_wvalid  = Output(Bool())
        val dbg_arvalid = Output(Bool())
      })

      val dcache = Module(new DCache)
      val mem    = Module(new Memory(1024)) // 4KB Memory
      val slave  = Module(new AXI4LiteSlave(32, 32))

      // ---------------- CPU <-> DCache ----------------
      dcache.io.cpu_req   := io.cpu_req
      dcache.io.cpu_addr  := io.cpu_addr
      dcache.io.cpu_we    := io.cpu_we
      dcache.io.cpu_wdata := io.cpu_wdata
      dcache.io.cpu_func3 := io.cpu_func3
      
      io.cpu_data         := dcache.io.cpu_data
      io.cpu_stall        := dcache.io.cpu_stall

      // ---------------- DCache Master <-> AXI Slave ----------------
      // Use bulk connection <> for nested bundles
      slave.io.channels <> dcache.io.axi

      // ---------------- Slave <-> Memory ----------------
      // Update variable access to nested channel names
      
      // Manual latch for address stability logic (as used in original test)
      val latchedWriteAddr = RegEnable(dcache.io.axi.write_address_channel.AWADDR, 
                                       dcache.io.axi.write_address_channel.AWVALID && dcache.io.axi.write_address_channel.AWREADY)
      
      // Select address: if writing, use latched Write Address; else use Read Address
      mem.io.bundle.address := Mux(slave.io.bundle.write, 
                                   latchedWriteAddr >> 2, 
                                   dcache.io.axi.read_address_channel.ARADDR >> 2)
      
      
      mem.io.bundle.write_enable := slave.io.bundle.write
      mem.io.bundle.write_data   := slave.io.bundle.write_data
      mem.io.bundle.write_strobe := slave.io.bundle.write_strobe

      // Slave Read Data input
      slave.io.bundle.read_data  := mem.io.bundle.read_data
      // Nested ARVALID access
      slave.io.bundle.read_valid := RegNext(dcache.io.axi.read_address_channel.ARVALID) // Simulate 1 cycle latency

      mem.io.instruction_address := 0.U
      mem.io.debug_read_address  := 0.U

      // ---------------- Debug VCD Outputs ----------------
      // Update debug signal sources
      io.dbg_awvalid := dcache.io.axi.write_address_channel.AWVALID
      io.dbg_wvalid  := dcache.io.axi.write_data_channel.WVALID
      io.dbg_arvalid := dcache.io.axi.read_address_channel.ARVALID

    }).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(2000)
      val targetAddr = 0x100
      val mmioAddr   = 0x20000000

      // Init
      dut.io.cpu_req.poke(false.B)
      dut.io.cpu_we.poke(false.B)
      dut.clock.step(5)

      println("=== Test 1: Write Word (Store) - Expect Write-Through ===")
      // CPU Store: sw 0xDEADBEEF to 0x100
      dut.io.cpu_addr.poke(targetAddr.U)
      dut.io.cpu_wdata.poke("hDEADBEEF".U)
      dut.io.cpu_we.poke(true.B)
      dut.io.cpu_func3.poke("b010".U) // sw
      dut.io.cpu_req.poke(true.B)

      dut.clock.step() 
      
      // Wait for Stall to clear (AXI Write Handshake)
      var cycles = 0
      while (dut.io.cpu_stall.peekBoolean() && cycles < 300) {
        dut.clock.step()
        cycles += 1
      }
      println(s"Write Stall Cycles: $cycles")
      assert(cycles > 0, "Write should trigger stall for AXI handshake")
      
      // Stop Request
      dut.io.cpu_req.poke(false.B)
      dut.io.cpu_we.poke(false.B)
      dut.clock.step(5)

      println("\n=== Test 2: Read Miss (Load) - Expect Refill ===")
      // CPU Load: lw from 0x100 (Should be in memory now)
      dut.io.cpu_addr.poke(targetAddr.U)
      dut.io.cpu_we.poke(false.B)
      dut.io.cpu_req.poke(true.B)

      // Wait for Stall (Refill)
      cycles = 0
      dut.clock.step() // enter FSM
      while (dut.io.cpu_stall.peekBoolean() && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      println(s"Read Miss Refill Cycles: $cycles")
      assert(cycles > 10, "Refill should take multiple cycles")

      val readData = dut.io.cpu_data.peekInt()
      println(f"Read Data: 0x$readData%08X")
      assert(readData == BigInt("DEADBEEF", 16), "Data read back should match written data")

      println("\n=== Test 3: Read Hit - Expect 0 Stall ===")
      // CPU Load again from 0x100
      dut.io.cpu_req.poke(false.B)
      dut.clock.step()
      dut.io.cpu_addr.poke(targetAddr.U)
      dut.io.cpu_req.poke(true.B)
      dut.clock.step() 
      
      assert(!dut.io.cpu_stall.peekBoolean(), "Cache Hit should not stall")
      assert(dut.io.cpu_data.peekInt() == BigInt("DEADBEEF", 16), "Hit data incorrect")
      println("Hit verified!")

      println("\n=== Test 4: MMIO Read - Expect Bypass ===")
      dut.io.cpu_req.poke(false.B)
      dut.clock.step(5)
      dut.io.cpu_addr.poke(mmioAddr.U)
      dut.io.cpu_req.poke(true.B)
      
      dut.clock.step() 
      cycles = 0
      while (dut.io.cpu_stall.peekBoolean() && cycles < 50) {
        dut.clock.step()
        cycles += 1
      }
      println(s"MMIO Access Cycles: $cycles")
      assert(cycles > 0, "MMIO should be slower than Hit (Bypass)")
    }
  }
}