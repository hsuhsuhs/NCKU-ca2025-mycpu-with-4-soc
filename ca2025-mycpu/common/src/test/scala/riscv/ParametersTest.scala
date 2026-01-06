// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv

import chisel3._
import chisel3.util.log2Up
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ParametersTest extends AnyFlatSpec with Matchers {
  behavior of "Parameters"

  it should "define correct address parameters" in {
    Parameters.AddrBits should be(32)
    Parameters.AddrWidth should be(32.W)
  }

  it should "define correct instruction parameters" in {
    Parameters.InstructionBits should be(32)
    Parameters.InstructionWidth should be(32.W)
  }

  it should "define correct data parameters" in {
    Parameters.DataBits should be(32)
    Parameters.DataWidth should be(32.W)
  }

  it should "define correct byte parameters" in {
    Parameters.ByteBits should be(8)
    Parameters.ByteWidth should be(8.W)
  }

  it should "calculate WordSize correctly from DataBits and ByteBits" in {
    val expected = Math.ceil(Parameters.DataBits.toDouble / Parameters.ByteBits.toDouble).toInt
    Parameters.WordSize should be(expected)
    Parameters.WordSize should be(4) // 32 bits / 8 bits = 4 bytes
  }

  it should "define correct physical register parameters" in {
    Parameters.PhysicalRegisters should be(32)
  }

  it should "calculate PhysicalRegisterAddrBits correctly" in {
    val expected = log2Up(Parameters.PhysicalRegisters)
    Parameters.PhysicalRegisterAddrBits should be(expected)
    Parameters.PhysicalRegisterAddrBits should be(5) // log2(32) = 5
  }

  it should "define PhysicalRegisterAddrWidth consistent with AddrBits" in {
    Parameters.PhysicalRegisterAddrWidth should be(Parameters.PhysicalRegisterAddrBits.W)
    Parameters.PhysicalRegisterAddrWidth should be(5.W)
  }

  it should "define correct CSR register parameters" in {
    Parameters.CSRRegisterAddrBits should be(12)
    Parameters.CSRRegisterAddrWidth should be(12.W)
  }

  it should "define correct interrupt flag parameters" in {
    Parameters.InterruptFlagBits should be(32)
    Parameters.InterruptFlagWidth should be(32.W)
  }

  it should "define correct hold/stall state parameters" in {
    Parameters.HoldStateBits should be(3)
    Parameters.StallStateWidth should be(3.W)
  }

  it should "define correct memory size parameters" in {
    Parameters.MemorySizeInBytes should be(32768) // 32 KB
    Parameters.MemorySizeInWords should be(8192) // 32768 / 4 = 8192 words
  }

  it should "calculate MemorySizeInWords correctly" in {
    val expected = Parameters.MemorySizeInBytes / 4
    Parameters.MemorySizeInWords should be(expected)
  }

  it should "define correct entry address" in {
    Parameters.EntryAddress.litValue.toLong should be(0x1000L)
  }

  it should "define correct device count parameters" in {
    Parameters.MasterDeviceCount should be(1)
    Parameters.SlaveDeviceCount should be(8)
  }

  it should "calculate SlaveDeviceCountBits correctly" in {
    val expected = log2Up(Parameters.SlaveDeviceCount)
    Parameters.SlaveDeviceCountBits should be(expected)
    Parameters.SlaveDeviceCountBits should be(3) // log2(8) = 3
  }

  it should "maintain consistency between related parameters" in {
    // Address consistency
    Parameters.AddrBits should be(Parameters.InstructionBits)
    Parameters.AddrBits should be(Parameters.DataBits)
    Parameters.AddrBits should be(Parameters.InterruptFlagBits)

    // Width consistency
    Parameters.AddrWidth should be(Parameters.InstructionWidth)
    Parameters.AddrWidth should be(Parameters.DataWidth)
    Parameters.AddrWidth should be(Parameters.InterruptFlagWidth)

    // WordSize should match DataBits/ByteBits ratio
    Parameters.WordSize should be(Parameters.DataBits / Parameters.ByteBits)

    // Memory alignment: MemorySizeInWords * 4 should equal MemorySizeInBytes
    Parameters.MemorySizeInWords * 4 should be(Parameters.MemorySizeInBytes)
  }

  it should "have reasonable memory size for RV32I processor" in {
    // Memory should be power of 2 or reasonable size
    Parameters.MemorySizeInBytes should be > 0
    Parameters.MemorySizeInBytes should be <= (1024 * 1024 * 16) // Max 16MB reasonable

    // Memory should be word-aligned (multiple of 4)
    Parameters.MemorySizeInBytes % 4 should be(0)
  }

  it should "have entry address within valid memory range" in {
    Parameters.EntryAddress.litValue.toLong should be >= 0L
    Parameters.EntryAddress.litValue.toLong should be < Parameters.MemorySizeInBytes.toLong
  }

  it should "have valid CSR address width per RISC-V specification" in {
    // RISC-V spec defines CSR addresses as 12-bit
    Parameters.CSRRegisterAddrBits should be(12)
  }

  it should "have valid register count per RISC-V specification" in {
    // RISC-V spec defines 32 general-purpose registers (x0-x31)
    Parameters.PhysicalRegisters should be(32)
  }

  it should "have valid data width per RISC-V RV32I specification" in {
    // RV32I requires 32-bit data width
    Parameters.DataBits should be(32)
  }

  it should "have sufficient address bits for memory size" in {
    // Address space should be large enough to address all memory
    val maxAddressable = 1L << Parameters.AddrBits
    maxAddressable should be >= Parameters.MemorySizeInBytes.toLong
  }

  it should "have log2Up calculations that produce minimal bit widths" in {
    // PhysicalRegisterAddrBits should be exactly log2Up(32) = 5
    (1 << Parameters.PhysicalRegisterAddrBits) should be >= Parameters.PhysicalRegisters
    (1 << (Parameters.PhysicalRegisterAddrBits - 1)) should be < Parameters.PhysicalRegisters

    // SlaveDeviceCountBits should be exactly log2Up(8) = 3
    (1 << Parameters.SlaveDeviceCountBits) should be >= Parameters.SlaveDeviceCount
    (1 << (Parameters.SlaveDeviceCountBits - 1)) should be < Parameters.SlaveDeviceCount
  }

  it should "define implementation types" in {
    ImplementationType.ThreeStage should be(0)
    ImplementationType.FiveStage should be(1)
  }
}
