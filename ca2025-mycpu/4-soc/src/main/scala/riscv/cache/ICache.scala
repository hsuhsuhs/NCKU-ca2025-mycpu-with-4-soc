package riscv.cache

import chisel3._
import chisel3.util._
import bus.AXI4LiteChannels // Use the standard definition provided by professor
import riscv.Parameters

class ICacheIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  val cpu_req      = Input(Bool())              // CPU fetch request
  val cpu_addr     = Input(UInt(addrWidth.W))   // address from CPU
  val cpu_data     = Output(UInt(dataWidth.W))  // instruction out
  val cpu_stall    = Output(Bool())             // stall signal to pipeline

  // AXI4-Lite Interface (Replaces manual M_AXI_* signals)
  val axi = new AXI4LiteChannels(addrWidth, dataWidth)

  // debug for VCD  
  val dbg_state = Output(UInt(3.W))
}

// FSM States
object ICacheState {
  val sIdleCompare :: sRefillRequest :: sRefillWait :: sUpdateTag :: Nil = Enum(4)
}

class ICache extends Module {
  val io = IO(new ICacheIO(Parameters.AddrBits, Parameters.DataBits))

  // Cache parameter
  val lineWords = 4
  val idxWidth  = 8
  val tagWidth  = 20

  val hitCnt  = RegInit(0.U(32.W))
  val missCnt = RegInit(0.U(32.W))
  
  // SRAM for data & tag
  val dataRAM = SyncReadMem(lineWords * (1 << idxWidth), UInt(32.W))
  val tagRAM  = SyncReadMem(1 << idxWidth, UInt(tagWidth.W))
  val validRAM = RegInit(VecInit(Seq.fill(1 << idxWidth)(false.B)))

  // Extract tag / index / offset
  val addrReg = RegEnable(io.cpu_addr, io.cpu_req && !io.cpu_stall)
  val currentAddr = Mux(io.cpu_stall, addrReg, io.cpu_addr)  
  val tagField  = currentAddr(31, 12)
  val idxField  = currentAddr(11, 4)
  val offset    = currentAddr(3, 2)

  val tagRead = tagRAM.read(idxField, io.cpu_req)
  val valid   = validRAM(idxField)
  val hit     = valid && (tagRead === tagField)

  // Pipeline stall control
  val stallReg = RegInit(false.B)
  io.cpu_stall := stallReg

  // Output data (combinational for hit)
  val dataAddr = Cat(idxField, offset)
  val dataOut = dataRAM.read(dataAddr, true.B)
  io.cpu_data := Mux(hit, dataOut, 0.U)

  // FSM
  val state        = RegInit(ICacheState.sIdleCompare)
  io.dbg_state := state.asUInt
  val refillCnt    = RegInit(0.U(2.W))
  val missBase     = RegInit(0.U(32.W))

  // ----------------------------------------------------------------
  // AXI4-Lite Default Signal Assignments
  // ----------------------------------------------------------------
  
  // 1. Write Channels: I-Cache is Read-Only, so we tie these to 0
  io.axi.write_address_channel.AWVALID := false.B
  io.axi.write_address_channel.AWADDR  := 0.U
  io.axi.write_address_channel.AWPROT  := 0.U
  
  io.axi.write_data_channel.WVALID     := false.B
  io.axi.write_data_channel.WDATA      := 0.U
  io.axi.write_data_channel.WSTRB      := 0.U
  
  io.axi.write_response_channel.BREADY := false.B

  // 2. Read Channels: Set default values (logic below will override when active)
  io.axi.read_address_channel.ARVALID := false.B
  io.axi.read_address_channel.ARADDR  := 0.U
  io.axi.read_address_channel.ARPROT  := 0.U
  
  io.axi.read_data_channel.RREADY     := false.B

  switch(state) {
    is(ICacheState.sIdleCompare) {
      stallReg := false.B
      when(io.cpu_req) {
        when(hit) {
          // Cache hit: serve data
          stallReg := false.B
          state := ICacheState.sIdleCompare
        }.otherwise {
          // Cache miss: latch base line address
          stallReg := true.B
          missBase := Cat(io.cpu_addr(31, 4), 0.U(4.W))
          refillCnt := 0.U
          state := ICacheState.sRefillRequest
        }
      }
    }

    is(ICacheState.sRefillRequest) {
      // Issue single-word AXI read
      stallReg := true.B
      
      // Access nested AXI signals
      io.axi.read_address_channel.ARVALID := true.B
      io.axi.read_address_channel.ARADDR  := missBase + (refillCnt << 2)

      when(io.axi.read_address_channel.ARREADY && io.axi.read_address_channel.ARVALID) {
        state := ICacheState.sRefillWait
      }
    }

    is(ICacheState.sRefillWait) {
      stallReg := true.B
      
      // Assert RREADY
      io.axi.read_data_channel.RREADY := true.B
      
      // Check RVALID
      when(io.axi.read_data_channel.RVALID && io.axi.read_data_channel.RREADY) {
        // write data to cache RAM
        val dataAddr = idxField * lineWords.U + refillCnt
        // Use RDATA from bundle
        dataRAM.write(dataAddr, io.axi.read_data_channel.RDATA)

        when(refillCnt === (lineWords - 1).U) {
          state := ICacheState.sUpdateTag
        }.otherwise {
          refillCnt := refillCnt + 1.U
          state := ICacheState.sRefillRequest
        }
      }
    }

    is(ICacheState.sUpdateTag) {
      stallReg := true.B
      // update tag + valid
      tagRAM.write(idxField, tagField)
      validRAM(idxField) := true.B
      // go back and re-service CPU
      state := ICacheState.sIdleCompare
    }
  }
}

