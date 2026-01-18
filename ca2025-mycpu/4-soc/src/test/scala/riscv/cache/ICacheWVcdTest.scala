package riscv.cache

import bus.AXI4LiteSlave
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import peripheral.Memory

class ICacheWVcdTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior.of("ICache with AXI4-Lite Memory System (VCD)")

  it should "refill cache line on miss and then hit" in {
    test(new Module {
      val io = IO(new Bundle {
        // CPU side
        val cpu_req   = Input(Bool())
        val cpu_addr  = Input(UInt(32.W))
        val cpu_data  = Output(UInt(32.W))
        val cpu_stall = Output(Bool())

        // Test write port
        val test_write_en   = Input(Bool())
        val test_write_addr = Input(UInt(32.W))
        val test_write_data = Input(UInt(32.W))

        // ---- Debug signals for VCD ----
        val dbg_state   = Output(UInt(3.W))
        val dbg_hit     = Output(Bool())
        val dbg_arvalid = Output(Bool())
        val dbg_rvalid  = Output(Bool())
      })

      val icache = Module(new ICache)
      val mem    = Module(new Memory(1024))
      val slave  = Module(new AXI4LiteSlave(32, 32))

      // ---------------- CPU <-> ICache ----------------
      icache.io.cpu_req  := io.cpu_req
      icache.io.cpu_addr := io.cpu_addr
      io.cpu_data        := icache.io.cpu_data
      io.cpu_stall       := icache.io.cpu_stall

      // ---------------- AXI Read Channel ----------------
      // Use bulk connection for cleaner code
      slave.io.channels.read_address_channel <> icache.io.axi.read_address_channel
      slave.io.channels.read_data_channel    <> icache.io.axi.read_data_channel

      // ---------------- Disable AXI write ----------------
      // I-Cache is read-only, so we disable write channels on the slave
      slave.io.channels.write_address_channel.AWADDR  := 0.U
      slave.io.channels.write_address_channel.AWVALID := false.B
      slave.io.channels.write_address_channel.AWPROT  := 0.U
      slave.io.channels.write_data_channel.WDATA      := 0.U
      slave.io.channels.write_data_channel.WVALID     := false.B
      slave.io.channels.write_data_channel.WSTRB      := 0.U
      slave.io.channels.write_response_channel.BREADY := false.B
      
      // Tie off unused I-Cache write outputs (already tied in ICache.scala, but good for safety)
      icache.io.axi.write_address_channel.AWREADY := false.B
      icache.io.axi.write_data_channel.WREADY     := false.B
      icache.io.axi.write_response_channel.BVALID := false.B
      icache.io.axi.write_response_channel.BRESP  := 0.U

      // ---------------- Memory ----------------
      val isTest = io.test_write_en

      // Access I-Cache Read Address via nested structure
      val axi_ar = icache.io.axi.read_address_channel

      mem.io.bundle.address :=
        Mux(isTest, io.test_write_addr >> 2, axi_ar.ARADDR >> 2)

      mem.io.bundle.write_enable := isTest
      mem.io.bundle.write_data   := io.test_write_data
      mem.io.bundle.write_strobe.foreach(_ := true.B)

      slave.io.bundle.read_data  := mem.io.bundle.read_data 
      slave.io.bundle.read_valid := RegNext(axi_ar.ARVALID)

      mem.io.instruction_address := 0.U
      mem.io.debug_read_address  := io.cpu_addr

      // ---------------- Debug (VCD) ----------------
      io.dbg_arvalid := axi_ar.ARVALID
      io.dbg_rvalid  := icache.io.axi.read_data_channel.RVALID
      io.dbg_hit     := !io.cpu_stall && io.cpu_req
      io.dbg_state   := icache.io.dbg_state   // <== Needs state to be a public val in ICache
    }).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      dut.clock.setTimeout(500)

      // ---------------- preload memory ----------------
      val targetAddr = 0x100
      dut.io.test_write_en.poke(true.B)
      dut.io.test_write_data.poke("hDEADBEEF".U)

      for (i <- 0 until 4) {
        dut.io.test_write_addr.poke((targetAddr + i * 4).U)
        dut.clock.step()
      }
      dut.io.test_write_en.poke(false.B)
      dut.clock.step(5)

      // ---------------- first fetch (MISS) ----------------
      dut.io.cpu_addr.poke(targetAddr.U)
      dut.io.cpu_req.poke(true.B)
      dut.clock.step() // Give FSM one cycle to enter Refill state
      var cycles = 0
      while (dut.io.cpu_stall.peekBoolean() && cycles < 50) {
        dut.clock.step()
        cycles += 1
      }

      println(s"Refill cycles = $cycles")
      assert(cycles > 0, "Cache did not enter refill")

      // ---------------- read data ----------------
      dut.clock.step()
      val data = dut.io.cpu_data.peekInt()
      println(f"Read data = 0x$data%08X")

      // Use BigInt to ensure comparison is safe from sign extension issues
      val expected = BigInt("DEADBEEF", 16)
      assert(data == expected, f"Refill data incorrect: expected 0x$expected%08X, got 0x$data%08X")

      // ---------------- second fetch (HIT) ----------------
      dut.io.cpu_req.poke(false.B)
      dut.clock.step()
      dut.io.cpu_req.poke(true.B)
      dut.clock.step()

      assert(!dut.io.cpu_stall.peekBoolean(), "Cache should hit on second access")
    }
  }
}
