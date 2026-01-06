package riscv.cache

import chisel3._
import chisel3.util._

class ICacheIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  val cpu_req      = Input(Bool())              // CPU fetch request
  val cpu_addr     = Input(UInt(addrWidth.W))   // address from CPU
  val cpu_data     = Output(UInt(dataWidth.W))  // instruction out
  val cpu_stall    = Output(Bool())             // stall signal to pipeline

  // AXI4-Lite Master Interface
  val M_AXI_ARADDR  = Output(UInt(addrWidth.W))
  val M_AXI_ARVALID = Output(Bool())
  val M_AXI_RDATA   = Input(UInt(dataWidth.W))
  val M_AXI_RVALID  = Input(Bool())
  val M_AXI_RREADY  = Output(Bool())
  val M_AXI_ARREADY = Input(Bool())

  // debug for VCD  
  val dbg_state = Output(UInt(3.W))
}

// FSM States
object ICacheState {
  val sIdleCompare :: sRefillRequest :: sRefillWait :: sUpdateTag :: Nil = Enum(4)
}

class ICache extends Module {
  val io = IO(new ICacheIO(32, 32))

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
  val state       = RegInit(ICacheState.sIdleCompare)
  io.dbg_state := state.asUInt
  val refillCnt   = RegInit(0.U(2.W))
  val missBase    = RegInit(0.U(32.W))

  // Default AXI signal
  io.M_AXI_ARVALID := false.B
  io.M_AXI_RREADY  := false.B
  io.M_AXI_ARADDR  := 0.U

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
      io.M_AXI_ARVALID := true.B
      io.M_AXI_ARADDR  := missBase + (refillCnt << 2)

      when(io.M_AXI_ARREADY && io.M_AXI_ARVALID) {
        state := ICacheState.sRefillWait
      }
    }

    is(ICacheState.sRefillWait) {
      stallReg := true.B
      io.M_AXI_RREADY := true.B
      when(io.M_AXI_RVALID && io.M_AXI_RREADY) {
        // write data to cache RAM
        val dataAddr = idxField * lineWords.U + refillCnt
        dataRAM.write(dataAddr, io.M_AXI_RDATA)

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

