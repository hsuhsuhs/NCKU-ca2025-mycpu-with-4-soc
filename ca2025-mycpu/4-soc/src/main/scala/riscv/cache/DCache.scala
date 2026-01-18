package riscv.cache

import chisel3._
import chisel3.util._
import bus.AXI4LiteChannels 
import riscv.Parameters    

class DCacheIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  // CPU Interface
  val cpu_req   = Input(Bool())
  val cpu_addr  = Input(UInt(addrWidth.W))
  val cpu_we    = Input(Bool())             // Write Enable
  val cpu_wdata = Input(UInt(dataWidth.W))  // Write Data
  val cpu_func3 = Input(UInt(3.W))          // Function code for Byte/Half/Word
  val cpu_data  = Output(UInt(dataWidth.W)) // Read Data
  val cpu_stall = Output(Bool())

  val axi = new AXI4LiteChannels(addrWidth, dataWidth)
}

object DCacheState {
  val sIdleCompare :: sRefillRequest :: sRefillWait :: sUpdateTag :: sReadMMIO :: sReadMMIOWait :: sWriteBus :: sWaitBValid :: Nil = Enum(8)
}

class DCache extends Module {
  val io = IO(new DCacheIO(Parameters.AddrBits, Parameters.DataBits))

  import DCacheState._

  // --- Parameters ---
  val lineWords = 4      // 16 Bytes per line
  val idxWidth  = 8      // 256 sets
  val tagWidth  = 20
  val offsetWidth = 2    // 4 bytes per word

  // --- Memories ---
  // DataRAM uses Vec(4, UInt(8.W)) to support masked writes (sb, sh, sw)
  val dataRAM = SyncReadMem(lineWords * (1 << idxWidth), Vec(4, UInt(8.W)))
  val tagRAM  = SyncReadMem(1 << idxWidth, UInt(tagWidth.W))
  val validRAM = RegInit(VecInit(Seq.fill(1 << idxWidth)(false.B)))

  // --- Address Parsing ---
  val tagField = io.cpu_addr(31, 12)
  val idxField = io.cpu_addr(11, 4)
  val offField = io.cpu_addr(3, 2) // Word offset

  // --- Hit Detection ---
  val memReadIndex = idxField
  val tagRead = tagRAM.read(memReadIndex, true.B)
  val hit = validRAM(idxField) && (tagRead === tagField)
  val isMMIO = io.cpu_addr >= 0x20000000.U

  // --- Write Strobe Generation ---
  val gen_wstrb = Wire(UInt(4.W))
  gen_wstrb := 0.U
  switch(io.cpu_func3) {
    is("b000".U) { gen_wstrb := "b0001".U << io.cpu_addr(1,0) } // sb
    is("b001".U) { gen_wstrb := "b0011".U << io.cpu_addr(1,0) } // sh
    is("b010".U) { gen_wstrb := "b1111".U }                     // sw
  }

  // --- Registers for FSM ---
  val state = RegInit(sIdleCompare)
  val stallReg = RegInit(false.B)
  
  val refillCnt = RegInit(0.U(2.W))
  val missBase  = RegInit(0.U(32.W))
  
  // Registers for Write Path Handshake
  val reg_waddr = Reg(UInt(32.W))
  val reg_wdata = Reg(UInt(32.W))
  val reg_wstrb = Reg(UInt(4.W))
  val aw_done   = RegInit(false.B)
  val w_done    = RegInit(false.B)

  // --- IO Assignments (Defaults) ---
  io.cpu_stall := stallReg

  // Default AXI Signals (Nested Structure)
  // Read Channels
  io.axi.read_address_channel.ARVALID := false.B
  io.axi.read_address_channel.ARADDR  := 0.U
  io.axi.read_address_channel.ARPROT  := 0.U
  io.axi.read_data_channel.RREADY     := false.B
  
  // Write Channels
  io.axi.write_address_channel.AWVALID := false.B
  io.axi.write_address_channel.AWADDR  := 0.U
  io.axi.write_address_channel.AWPROT  := 0.U
  
  io.axi.write_data_channel.WVALID     := false.B
  io.axi.write_data_channel.WDATA      := 0.U
  io.axi.write_data_channel.WSTRB      := 0.U
  
  io.axi.write_response_channel.BREADY := false.B

  // Data Read Mux
  val ramReadAddr = Cat(idxField, offField)
  val dataRamOutVec = dataRAM.read(ramReadAddr, true.B) 
  val dataRamOut = dataRamOutVec.asUInt 
  
  // Access RDATA via nested channel
  io.cpu_data := Mux(state === sReadMMIOWait, io.axi.read_data_channel.RDATA, Mux(hit, dataRamOut, 0.U))


  // --- FSM ---
  switch(state) {
    is(sIdleCompare) {
      // Logic for CPU request
      when(io.cpu_req) {
        // --- WRITE PATH ---
        when(io.cpu_we) {
          stallReg := true.B // Writes always stall for AXI safety
          
          // Latch Write Data
          reg_waddr := io.cpu_addr
          reg_wdata := io.cpu_wdata
          reg_wstrb := gen_wstrb
          
          // Write-Through: Update Cache on Hit (and not MMIO)
          when(hit && !isMMIO) {
             val wdataVec = Wire(Vec(4, UInt(8.W)))
             wdataVec(0) := io.cpu_wdata(7, 0)
             wdataVec(1) := io.cpu_wdata(15, 8)
             wdataVec(2) := io.cpu_wdata(23, 16)
             wdataVec(3) := io.cpu_wdata(31, 24)
             
             val maskVec = VecInit(gen_wstrb.asBools)
             dataRAM.write(ramReadAddr, wdataVec, maskVec)
          }
          
          // Reset Handshake flags
          aw_done := false.B
          w_done  := false.B
          state := sWriteBus
        }
        // --- READ PATH ---
        .otherwise {
          when(isMMIO) {
            // MMIO Read Bypass
            stallReg := true.B
            state := sReadMMIO
          }
          .elsewhen(hit) {
            // Cache Hit
            stallReg := false.B
            state := sIdleCompare
          }
          .otherwise {
            // Cache Miss -> Refill
            stallReg := true.B
            missBase := Cat(io.cpu_addr(31, 4), 0.U(4.W))
            refillCnt := 0.U
            state := sRefillRequest
          }
        }
      }.otherwise {
        stallReg := false.B
      }
    }

    // =========================================================
    // READ PATH: Refill (Cacheable)
    // =========================================================
    is(sRefillRequest) {
      stallReg := true.B
      // Read Address Channel
      io.axi.read_address_channel.ARVALID := true.B
      io.axi.read_address_channel.ARADDR  := missBase + (refillCnt << 2)

      when(io.axi.read_address_channel.ARREADY && io.axi.read_address_channel.ARVALID) {
        state := sRefillWait
      }
    }

    is(sRefillWait) {
      stallReg := true.B
      // Read Data Channel
      io.axi.read_data_channel.RREADY := true.B

      when(io.axi.read_data_channel.RVALID && io.axi.read_data_channel.RREADY) {
        // Write received word to Data RAM
        val refillAddr = Cat(idxField, refillCnt) 
        val wdataVec = Wire(Vec(4, UInt(8.W)))
        
        // Get data from channel
        val rdata = io.axi.read_data_channel.RDATA
        wdataVec(0) := rdata(7, 0)
        wdataVec(1) := rdata(15, 8)
        wdataVec(2) := rdata(23, 16)
        wdataVec(3) := rdata(31, 24)
        
        val maskAll = VecInit(Seq.fill(4)(true.B))
        dataRAM.write(refillAddr, wdataVec, maskAll)

        when(refillCnt === (lineWords - 1).U) {
          state := sUpdateTag
        } .otherwise {
          refillCnt := refillCnt + 1.U
          state := sRefillRequest
        }
      }
    }

    is(sUpdateTag) {
      stallReg := true.B
      tagRAM.write(idxField, tagField)
      validRAM(idxField) := true.B
      state := sIdleCompare
    }

    // =========================================================
    // READ PATH: MMIO (Uncacheable)
    // =========================================================
    is(sReadMMIO) {
      stallReg := true.B
      // Read Address Channel
      io.axi.read_address_channel.ARVALID := true.B
      io.axi.read_address_channel.ARADDR  := io.cpu_addr 

      when(io.axi.read_address_channel.ARREADY && io.axi.read_address_channel.ARVALID) {
        state := sReadMMIOWait
      }
    }

    is(sReadMMIOWait) {
      stallReg := true.B
      io.axi.read_data_channel.RREADY := true.B
      
      when(io.axi.read_data_channel.RVALID && io.axi.read_data_channel.RREADY) {
        // Data is passed via Mux above
        state := sIdleCompare
      }
    }

    // =========================================================
    // WRITE PATH (Store)
    // =========================================================
    is(sWriteBus) {
      stallReg := true.B
      
      // Write Address Channel
      io.axi.write_address_channel.AWVALID := !aw_done
      io.axi.write_address_channel.AWADDR  := reg_waddr
      
      // Write Data Channel
      io.axi.write_data_channel.WVALID     := !w_done
      io.axi.write_data_channel.WDATA      := reg_wdata
      io.axi.write_data_channel.WSTRB      := reg_wstrb

      // Handshake Logic
      when(io.axi.write_address_channel.AWREADY && io.axi.write_address_channel.AWVALID) { aw_done := true.B }
      when(io.axi.write_data_channel.WREADY     && io.axi.write_data_channel.WVALID)     { w_done  := true.B }

      // Wait for BOTH handshakes
      when((aw_done || io.axi.write_address_channel.AWREADY) && (w_done || io.axi.write_data_channel.WREADY)) {
        state := sWaitBValid
      }
    }

    is(sWaitBValid) {
      stallReg := true.B
      // Write Response Channel
      io.axi.write_response_channel.BREADY := true.B
      
      when(io.axi.write_response_channel.BVALID && io.axi.write_response_channel.BREADY) {
        state := sIdleCompare
      }
    }
  }
}