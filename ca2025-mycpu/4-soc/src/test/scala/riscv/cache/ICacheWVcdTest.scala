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
      slave.io.channels.read_address_channel.ARADDR  := icache.io.M_AXI_ARADDR
      slave.io.channels.read_address_channel.ARVALID := icache.io.M_AXI_ARVALID
      slave.io.channels.read_address_channel.ARPROT  := 0.U
      icache.io.M_AXI_ARREADY                        := slave.io.channels.read_address_channel.ARREADY

      icache.io.M_AXI_RDATA                      := slave.io.channels.read_data_channel.RDATA
      icache.io.M_AXI_RVALID                     := slave.io.channels.read_data_channel.RVALID
      slave.io.channels.read_data_channel.RREADY := icache.io.M_AXI_RREADY

      // ---------------- Disable AXI write ----------------
      slave.io.channels.write_address_channel.AWADDR  := 0.U
      slave.io.channels.write_address_channel.AWVALID := false.B
      slave.io.channels.write_address_channel.AWPROT  := 0.U
      slave.io.channels.write_data_channel.WDATA      := 0.U
      slave.io.channels.write_data_channel.WVALID     := false.B
      slave.io.channels.write_data_channel.WSTRB      := 0.U
      slave.io.channels.write_response_channel.BREADY := false.B

      // ---------------- Memory ----------------
      val isTest = io.test_write_en

      mem.io.bundle.address :=
        Mux(isTest, io.test_write_addr >> 2, icache.io.M_AXI_ARADDR >> 2)

      mem.io.bundle.write_enable := isTest
      mem.io.bundle.write_data   := io.test_write_data
      mem.io.bundle.write_strobe.foreach(_ := true.B)

      slave.io.bundle.read_data  := mem.io.bundle.read_data 
      slave.io.bundle.read_valid := RegNext(icache.io.M_AXI_ARVALID)

      mem.io.instruction_address := 0.U
      mem.io.debug_read_address  := io.cpu_addr

      // ---------------- Debug (VCD) ----------------
      io.dbg_arvalid := icache.io.M_AXI_ARVALID
      io.dbg_rvalid  := icache.io.M_AXI_RVALID
      io.dbg_hit     := !io.cpu_stall && io.cpu_req
      io.dbg_state   := icache.io.dbg_state   // <== 需要 state 是 public val
    }).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      dut.clock.setTimeout(500)

      // ---------------- Step 1: preload memory ----------------
      val targetAddr = 0x100
      dut.io.test_write_en.poke(true.B)
      dut.io.test_write_data.poke("hDEADBEEF".U)

      for (i <- 0 until 4) {
        dut.io.test_write_addr.poke((targetAddr + i * 4).U)
        dut.clock.step()
      }
      dut.io.test_write_en.poke(false.B)
      dut.clock.step(5)

      // ---------------- Step 2: first fetch (MISS) ----------------
      dut.io.cpu_addr.poke(targetAddr.U)
      dut.io.cpu_req.poke(true.B)
      dut.clock.step() //給 FSM 一個週期進入 Refill 狀態
      var cycles = 0
      while (dut.io.cpu_stall.peekBoolean() && cycles < 50) {
        dut.clock.step()
        cycles += 1
      }

      println(s"Refill cycles = $cycles")
      assert(cycles > 0, "Cache did not enter refill")

      // ---------------- Step 3: read data ----------------
      dut.clock.step()
      val data = dut.io.cpu_data.peekInt()
      println(f"Read data = 0x$data%08X")

      // 使用 BigInt 確保比較時不會因為正負號出錯
      val expected = BigInt("DEADBEEF", 16)
      assert(data == expected, f"Refill data incorrect: expected 0x$expected%08X, got 0x$data%08X")

      // ---------------- Step 4: second fetch (HIT) ----------------
      dut.io.cpu_req.poke(false.B)
      dut.clock.step()
      dut.io.cpu_req.poke(true.B)
      dut.clock.step()

      assert(!dut.io.cpu_stall.peekBoolean(), "Cache should hit on second access")
    }
  }
}
