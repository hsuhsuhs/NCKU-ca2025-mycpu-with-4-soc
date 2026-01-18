package bus

import chisel3._
import chisel3.util._

class AXI4LiteArbiter(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    // Master 0: I-Cache (Read Only)
    val m0 = Flipped(new AXI4LiteChannels(addrWidth, dataWidth))
    
    // Master 1: D-Cache (Read / Write) - High Priority
    val m1 = Flipped(new AXI4LiteChannels(addrWidth, dataWidth))

    // Output to Slave (BusSwitch)
    val out = new AXI4LiteChannels(addrWidth, dataWidth)
  })

  // ==============================================================================
  // Write Channel (D-Cache Priority / Pass-through)
  
  // D-Cache (m1) has exclusive access to the Write Channel
  io.out.write_address_channel <> io.m1.write_address_channel
  io.out.write_data_channel    <> io.m1.write_data_channel
  io.out.write_response_channel <> io.m1.write_response_channel

  // I-Cache (m0) is Read-Only. 
  // Terminate the Write Channel signals going BACK to m0 (outputs).
  
  io.m0.write_address_channel.AWREADY := false.B
  
  io.m0.write_data_channel.WREADY     := false.B
  
  io.m0.write_response_channel.BVALID := false.B
  io.m0.write_response_channel.BRESP  := 0.U


  // ==============================================================================
  // Read Channel (Arbitration Logic)

  val sIdle :: sReadM0 :: sReadM1 :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // Default Output values (to Slave)
  io.out.read_address_channel.ARVALID := false.B
  io.out.read_address_channel.ARADDR  := 0.U
  io.out.read_address_channel.ARPROT  := 0.U
  io.out.read_data_channel.RREADY     := false.B

  // Default Response values (to Masters)
  io.m0.read_address_channel.ARREADY := false.B
  io.m0.read_data_channel.RVALID     := false.B
  io.m0.read_data_channel.RDATA      := 0.U
  io.m0.read_data_channel.RRESP      := 0.U

  io.m1.read_address_channel.ARREADY := false.B
  io.m1.read_data_channel.RVALID     := false.B
  io.m1.read_data_channel.RDATA      := 0.U
  io.m1.read_data_channel.RRESP      := 0.U

  switch(state) {
    is(sIdle) {
      // Priority: D-Cache (m1) > I-Cache (m0)
      when(io.m1.read_address_channel.ARVALID) {
        // Connect m1 to out
        io.out.read_address_channel <> io.m1.read_address_channel
        
        // If handshake happens immediately
        when(io.out.read_address_channel.ARREADY) {
          state := sReadM1
        }
      } 
      .elsewhen(io.m0.read_address_channel.ARVALID) {
        // Connect m0 to out
        io.out.read_address_channel <> io.m0.read_address_channel
        
        // If handshake happens immediately
        when(io.out.read_address_channel.ARREADY) {
          state := sReadM0
        }
      }
    }

    is(sReadM1) {
      // Forward Read Data from Slave (out) to D-Cache (m1)
      io.m1.read_data_channel <> io.out.read_data_channel
      
      // Wait for transaction to complete
      when(io.out.read_data_channel.RVALID && io.m1.read_data_channel.RREADY) {
        state := sIdle
      }
    }

    is(sReadM0) {
      // Forward Read Data from Slave (out) to I-Cache (m0)
      io.m0.read_data_channel <> io.out.read_data_channel
      
      // Wait for transaction to complete
      when(io.out.read_data_channel.RVALID && io.m0.read_data_channel.RREADY) {
        state := sIdle
      }
    }
  }
}