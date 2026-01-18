// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import riscv.Parameters

class CPUBundle extends Bundle {
  // ==============================================================
  // 1. Instruction Interface (Connects to I-Cache)
  // ==============================================================
  // The address the CPU wants to fetch (Program Counter)
  val instruction_address = Output(UInt(Parameters.AddrWidth))
  
  // The instruction returned by the I-Cache
  val instruction         = Input(UInt(Parameters.InstructionWidth))
  
  // Indicates if the instruction is valid (I-Cache Hit)
  val instruction_valid   = Input(Bool()) 

  // ==============================================================
  // 2. Memory Interface (Connects to D-Cache)
  // ==============================================================
  // We define an explicit bundle here to ensure 'func3' is included
  val memory_bundle = new Bundle {
    val request     = Output(Bool())              // CPU requests memory access
    val address     = Output(UInt(Parameters.AddrWidth))
    val write_data  = Output(UInt(Parameters.DataWidth))
    val write       = Output(Bool())              // Write Enable
    val read        = Output(Bool())              // Read Enable
    
    // Data width control: 000(sb), 001(sh), 010(sw)
    // Critical for D-Cache to generate correct Write Strobes (WSTRB)
    val func3       = Output(UInt(3.W))           
    
    // Signals returned from D-Cache
    val read_data   = Input(UInt(Parameters.DataWidth))
    val read_valid  = Input(Bool())               // D-Cache Hit / Data Valid
    val busy        = Input(Bool())               // D-Cache is busy (Miss/Refill), CPU must wait
  }

  // ==============================================================
  // 3. Stall Control (Global Pipeline Control)
  // ==============================================================
  // Asserted when I-Cache misses (Stalls Fetch stage)
  val stall_frontend = Input(Bool()) 
  
  // Asserted when D-Cache misses (Stalls Memory/Writeback stages)
  // Usually causes a global pipeline freeze
  val stall_backend  = Input(Bool()) 

  // ==============================================================
  // 4. Interrupts & Debugging (Passthrough)
  // ==============================================================
  val interrupt_flag = Input(UInt(Parameters.InterruptFlagWidth))

  // General Purpose Register (GPR) Debug Port
  val debug_read_address = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
  val debug_read_data    = Output(UInt(Parameters.DataWidth))

  // Control Status Register (CSR) Debug Port
  val csr_debug_read_address = Input(UInt(Parameters.CSRRegisterAddrWidth))
  val csr_debug_read_data    = Output(UInt(Parameters.DataWidth))

  // Optional: Debug Bus Write (Used by Testbench to force writes)
  val debug_bus_write_enable = Output(Bool())
  val debug_bus_write_data   = Output(UInt(Parameters.DataWidth))
  
  // Note: 'axi4_channels' and 'bus_address' have been removed.
  // The CPU now delegates AXI protocol handling to the I/D Caches.
}
