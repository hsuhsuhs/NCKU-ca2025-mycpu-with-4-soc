// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv

import bus.AXI4LiteSlave
import bus.AXI4LiteArbiter // [NEW] Needed to arbitrate between I/D Caches
import chisel3._
import peripheral.InstructionROM
import peripheral.Memory
import peripheral.ROMLoader
import riscv.core.CPU
import riscv.cache._       // [NEW] Needed for ICache/DCache

// Simplified test harness for RISCOF compliance tests
// Uses AXI4-Lite to connect CPU to Memory, matching the 4-soc architecture
class TestTopModule(exeFilename: String) extends Module {
  val io = IO(new Bundle {
    val regs_debug_read_address = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val mem_debug_read_address  = Input(UInt(Parameters.AddrWidth))
    val regs_debug_read_data    = Output(UInt(Parameters.DataWidth))
    val mem_debug_read_data     = Output(UInt(Parameters.DataWidth))
    val csr_debug_read_address  = Input(UInt(Parameters.CSRRegisterAddrWidth))
    val csr_debug_read_data     = Output(UInt(Parameters.DataWidth))
    val interrupt_flag          = Input(UInt(Parameters.InterruptFlagWidth))
  })

  val mem             = Module(new Memory(8192))
  val instruction_rom = Module(new InstructionROM(exeFilename))
  val rom_loader      = Module(new ROMLoader(instruction_rom.capacity))

  rom_loader.io.rom_data     := instruction_rom.io.data
  rom_loader.io.load_address := Parameters.EntryAddress
  instruction_rom.io.address := rom_loader.io.rom_address

  // Clock divider for CPU (4:1 ratio for AXI4-Lite timing compatibility)
  val CPU_clkdiv = RegInit(UInt(2.W), 0.U)
  val CPU_tick   = Wire(Bool())
  val CPU_next   = Wire(UInt(2.W))
  CPU_next   := Mux(CPU_clkdiv === 3.U, 0.U, CPU_clkdiv + 1.U)
  CPU_tick   := CPU_clkdiv === 0.U
  CPU_clkdiv := CPU_next

  withClock(CPU_tick.asClock) {
    val cpu = Module(new CPU)

    // [NEW] Instantiate Caches and Arbiter
    // We must manually wire these because the CPU no longer speaks AXI directly
    val icache  = Module(new ICache())
    val dcache  = Module(new DCache())
    val arbiter = Module(new AXI4LiteArbiter(Parameters.AddrBits, Parameters.DataBits))

    // AXI4-Lite slave adapter for memory
    val mem_slave = Module(new AXI4LiteSlave(Parameters.AddrBits, Parameters.DataBits))

    cpu.io.debug_read_address     := 0.U
    cpu.io.csr_debug_read_address := 0.U
    
    // ----------------------------------------------------------------
    // Instruction Path (CPU -> ICache)
    
    // Flag to indicate ROM loading is done
    val load_finished = rom_loader.io.load_finished
    
    // Connect CPU to ICache
    icache.io.cpu_req  := load_finished // Only request fetch when loading is done
    icache.io.cpu_addr := cpu.io.instruction_address
    
    cpu.io.instruction       := icache.io.cpu_data
    // CPU only proceeds if ICache is not stalling AND loading is finished
    cpu.io.instruction_valid := load_finished && !icache.io.cpu_stall
    cpu.io.stall_frontend    := icache.io.cpu_stall

    // ----------------------------------------------------------------
    // Memory Path (CPU -> DCache)
   
    dcache.io.cpu_req   := cpu.io.memory_bundle.request
    dcache.io.cpu_addr  := cpu.io.memory_bundle.address
    dcache.io.cpu_we    := cpu.io.memory_bundle.write
    dcache.io.cpu_wdata := cpu.io.memory_bundle.write_data
    dcache.io.cpu_func3 := cpu.io.memory_bundle.func3 // Critical for correct strobe

    cpu.io.memory_bundle.read_data  := dcache.io.cpu_data
    cpu.io.memory_bundle.read_valid := !dcache.io.cpu_stall
    cpu.io.memory_bundle.busy       := dcache.io.cpu_stall
    
    cpu.io.stall_backend := dcache.io.cpu_stall

    // ----------------------------------------------------------------
    // AXI Connections (Caches -> Arbiter -> Memory Slave)
   
    arbiter.io.m0 <> icache.io.axi // I-Cache to Port 0
    arbiter.io.m1 <> dcache.io.axi // D-Cache to Port 1 (Higher Priority)
    
    mem_slave.io.channels <> arbiter.io.out

    // Interrupts
    cpu.io.interrupt_flag := io.interrupt_flag

    // ----------------------------------------------------------------
    // Memory Multiplexing (Loading vs. AXI Slave)
    
    val loading = !rom_loader.io.load_finished

    // Select between ROMLoader (initializing memory) and AXI Slave (CPU execution)
    mem.io.bundle.address      := Mux(loading, rom_loader.io.bundle.address, mem_slave.io.bundle.address)
    mem.io.bundle.write_data   := Mux(loading, rom_loader.io.bundle.write_data, mem_slave.io.bundle.write_data)
    mem.io.bundle.write_enable := Mux(loading, rom_loader.io.bundle.write_enable, mem_slave.io.bundle.write)
    mem.io.bundle.write_strobe := Mux(loading, rom_loader.io.bundle.write_strobe, mem_slave.io.bundle.write_strobe)

    // ROMLoader read_data
    rom_loader.io.bundle.read_data := mem.io.bundle.read_data

    // AXI slave read responses
    // Memory is SyncReadMem with 1-cycle read latency.
    val read_pending = RegNext(mem_slave.io.bundle.read && !loading, false.B)
    mem_slave.io.bundle.read_data  := mem.io.bundle.read_data
    mem_slave.io.bundle.read_valid := read_pending

    // Debug interfaces
    cpu.io.debug_read_address     := io.regs_debug_read_address
    io.regs_debug_read_data       := cpu.io.debug_read_data
    cpu.io.csr_debug_read_address := io.csr_debug_read_address
    io.csr_debug_read_data        := cpu.io.csr_debug_read_data

    // Terminate direct instruction fetch port (unused, handled by ICache via AXI)
    mem.io.instruction_address := 0.U
  }

  mem.io.debug_read_address := io.mem_debug_read_address
  io.mem_debug_read_data    := mem.io.debug_read_data
}
