// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package board.verilator

import bus._              // Import all bus components (AXI4Lite, Arbiter, Switch)
import chisel3._
import chisel3.stage.ChiselStage
import peripheral.DummySlave
import peripheral.Uart
import peripheral.VGA
import riscv.core.CPU
import riscv.Parameters
import riscv.cache._      // [NEW] Import Cache modules

class Top extends Module {
  val io = IO(new Bundle {
    val signal_interrupt = Input(Bool())

    // Instruction interface 
    // Kept for testbench compatibility, but the CPU now fetches from I-Cache -> AXI
    val instruction_address = Output(UInt(Parameters.AddrWidth))
    val instruction         = Input(UInt(Parameters.InstructionWidth))
    val instruction_valid   = Input(Bool())

    val mem_slave = new AXI4LiteSlaveBundle(Parameters.AddrBits, Parameters.DataBits)

    // VGA peripheral outputs
    val vga_pixclk      = Input(Clock())
    val vga_hsync       = Output(Bool())
    val vga_vsync       = Output(Bool())
    val vga_rrggbb      = Output(UInt(6.W))
    val vga_activevideo = Output(Bool())
    val vga_x_pos       = Output(UInt(10.W))
    val vga_y_pos       = Output(UInt(10.W))

    // UART peripheral outputs
    val uart_txd       = Output(UInt(1.W))
    val uart_rxd       = Input(UInt(1.W))
    val uart_interrupt = Output(Bool())

    // Debug interfaces
    val cpu_debug_read_address     = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val cpu_debug_read_data        = Output(UInt(Parameters.DataWidth))
    val cpu_csr_debug_read_address = Input(UInt(Parameters.CSRRegisterAddrWidth))
    val cpu_csr_debug_read_data    = Output(UInt(Parameters.DataWidth))
  })

  // AXI4-Lite memory model provided by Verilator C++ harness
  val mem_slave = Module(new AXI4LiteSlave(Parameters.AddrBits, Parameters.DataBits))
  io.mem_slave <> mem_slave.io.bundle

  // Peripherals
  val vga   = Module(new VGA)
  val uart  = Module(new Uart(frequency = 50000000, baudRate = 115200))
  val dummy = Module(new DummySlave)
  
  val bus_switch = Module(new BusSwitch)

  // [NEW] Instantiate CPU, Caches, and the new AXI Arbiter
  val cpu     = Module(new CPU)
  val icache  = Module(new ICache())
  val dcache  = Module(new DCache())
  val arbiter = Module(new AXI4LiteArbiter(Parameters.AddrBits, Parameters.DataBits))

  // =================================================================
  // Connect Instruction Path (CPU <-> I-Cache)
  
  // CPU requests instruction from I-Cache
  icache.io.cpu_req  := true.B                     // Always request fetch
  icache.io.cpu_addr := cpu.io.instruction_address
  
  // CPU receives instruction from I-Cache
  cpu.io.instruction := icache.io.cpu_data
  
  // CPU Stalls if I-Cache is busy (Miss/Refill)
  // We assume valid if not stalling. 
  cpu.io.instruction_valid := !icache.io.cpu_stall 
  cpu.io.stall_frontend    := icache.io.cpu_stall

  // Forward address to Top IO for Testbench visibility
  io.instruction_address := cpu.io.instruction_address

  // =================================================================
  // Connect Memory Path (CPU <-> D-Cache)
 
  // CPU sends request to D-Cache
  dcache.io.cpu_req   := cpu.io.memory_bundle.request
  dcache.io.cpu_addr  := cpu.io.memory_bundle.address
  dcache.io.cpu_we    := cpu.io.memory_bundle.write
  dcache.io.cpu_wdata := cpu.io.memory_bundle.write_data
  dcache.io.cpu_func3 := cpu.io.memory_bundle.func3 // Critical for sb/sh/sw
  
  // CPU receives data/status from D-Cache
  cpu.io.memory_bundle.read_data  := dcache.io.cpu_data
  // Simple valid logic: if D-Cache is not stalling, data is valid
  cpu.io.memory_bundle.read_valid := !dcache.io.cpu_stall 
  cpu.io.memory_bundle.busy       := dcache.io.cpu_stall
  
  // Connect Stall to backend (freezes pipeline during D-Cache Miss)
  cpu.io.stall_backend := dcache.io.cpu_stall

  // =================================================================
  // Connect AXI Bus (Caches <-> Arbiter <-> BusSwitch)
  
  // Connect I-Cache (Master 0) to Arbiter
  arbiter.io.m0 <> icache.io.axi

  // Connect D-Cache (Master 1) to Arbiter
  arbiter.io.m1 <> dcache.io.axi

  // Connect Arbiter Output to Bus Switch Master Port
  bus_switch.io.master <> arbiter.io.out

  // [IMPORTANT] Address Routing Logic for BusSwitch
  // The Switch needs a single address to decode which slave to activate.
  // We check if a Write is happening (AWVALID), otherwise we use Read Address (ARADDR).
  bus_switch.io.address := Mux(arbiter.io.out.write_address_channel.AWVALID, 
                               arbiter.io.out.write_address_channel.AWADDR, 
                               arbiter.io.out.read_address_channel.ARADDR)

  // =================================================================
  // Connect Slaves to Bus Switch (Unchanged)
 
  bus_switch.io.slaves(0) <> mem_slave.io.channels
  bus_switch.io.slaves(1) <> vga.io.channels
  bus_switch.io.slaves(2) <> uart.io.channels
  for (i <- 3 until Parameters.SlaveDeviceCount) {
    bus_switch.io.slaves(i) <> dummy.io.channels
  }

  // =================================================================
  // Connect Peripherals IO (Unchanged)
  
  // VGA
  vga.io.pixClock    := io.vga_pixclk
  io.vga_hsync       := vga.io.hsync
  io.vga_vsync       := vga.io.vsync
  io.vga_rrggbb      := vga.io.rrggbb
  io.vga_activevideo := vga.io.activevideo
  io.vga_x_pos       := vga.io.x_pos
  io.vga_y_pos       := vga.io.y_pos

  // UART
  io.uart_txd        := uart.io.txd
  uart.io.rxd        := io.uart_rxd
  io.uart_interrupt  := uart.io.signal_interrupt
  cpu.io.interrupt_flag := io.signal_interrupt

  // Debugging
  cpu.io.debug_read_address      := io.cpu_debug_read_address
  io.cpu_debug_read_data         := cpu.io.debug_read_data
  cpu.io.csr_debug_read_address  := io.cpu_csr_debug_read_address
  io.cpu_csr_debug_read_data     := cpu.io.csr_debug_read_data
}

object VerilogGenerator extends App {
  (new ChiselStage).emitVerilog(
    new Top(),
    Array("--target-dir", "4-soc/verilog/verilator")
  )
}