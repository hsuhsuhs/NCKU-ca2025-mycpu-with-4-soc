// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import riscv.ImplementationType
import riscv.Parameters

class CPU(val implementation: Int = ImplementationType.FiveStageFinal) extends Module {
  val io = IO(new CPUBundle)

  implementation match {
    case ImplementationType.FiveStageFinal =>
      val cpu = Module(new PipelinedCPU)

      // --------------------------------------------------------------------------------
      // 1. Instruction Fetch Interface
      // --------------------------------------------------------------------------------
      // Connect instruction fetch interface
      io.instruction_address   := cpu.io.instruction_address
      cpu.io.instruction       := io.instruction
      cpu.io.instruction_valid := io.instruction_valid
      
      // Connect Frontend Stall (I-Cache Miss)
      // Allows the I-Cache to stall the fetch stage directly
      cpu.io.stall_frontend := io.stall_frontend

      // --------------------------------------------------------------------------------
      // 2. Memory Interface (D-Cache Connection)
      // --------------------------------------------------------------------------------
      // NOTE: The internal AXI4-Lite master and address latching logic have been removed.
      // The CPU now connects directly to the D-Cache via the CPUBundle.
      
      // Connect memory bundle outputs (pass through from CPU to IO)
      io.memory_bundle.request    := cpu.io.memory_bundle.request
      io.memory_bundle.address    := cpu.io.memory_bundle.address
      io.memory_bundle.write      := cpu.io.memory_bundle.write
      io.memory_bundle.read       := cpu.io.memory_bundle.read
      io.memory_bundle.write_data := cpu.io.memory_bundle.write_data
      
      // Pass func3 (Data Width) to D-Cache for correct strobe generation
      io.memory_bundle.func3      := cpu.io.memory_bundle.func3

      // Connect memory bundle inputs (pass through from IO to CPU)
      cpu.io.memory_bundle.read_data  := io.memory_bundle.read_data
      cpu.io.memory_bundle.read_valid := io.memory_bundle.read_valid
      cpu.io.memory_bundle.busy       := io.memory_bundle.busy

      // Connect Backend Stall (D-Cache Miss)
      // Allows the D-Cache to stall the memory stage directly
      cpu.io.stall_backend := io.stall_backend

      // --------------------------------------------------------------------------------
      // 3. Interrupts & Debug Interfaces
      // --------------------------------------------------------------------------------
      // Connect interrupt
      cpu.io.interrupt_flag := io.interrupt_flag

      // Connect debug interfaces
      cpu.io.debug_read_address := io.debug_read_address
      io.debug_read_data        := cpu.io.debug_read_data

      cpu.io.csr_debug_read_address := io.csr_debug_read_address
      io.csr_debug_read_data        := cpu.io.csr_debug_read_data

      // Connect debug bus signals
      io.debug_bus_write_enable := cpu.io.debug_bus_write_enable
      io.debug_bus_write_data   := cpu.io.debug_bus_write_data
  }
}