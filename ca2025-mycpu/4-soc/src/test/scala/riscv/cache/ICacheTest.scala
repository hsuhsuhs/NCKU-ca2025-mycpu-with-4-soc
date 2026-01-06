package riscv.cache

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ICacheSpec extends AnyFlatSpec with ChiselScalatestTester {
  "ICache" should "handle miss, refill 4 words, and then hit" in {
    test(new ICache) { dut =>
      // 初始化 AXI 訊號
      dut.io.M_AXI_ARREADY.poke(false.B)
      dut.io.M_AXI_RVALID.poke(false.B)
      dut.io.cpu_req.poke(false.B)

      // --- Step 1: 請求一個地址 (Miss) ---
      dut.io.cpu_addr.poke("h0000_1000".U)
      dut.io.cpu_req.poke(true.B)
      dut.clock.step() // 讓 SyncReadMem 讀取

      // 此時應該發生 Miss，stall 拉高，ARVALID 拉高
      dut.io.cpu_stall.expect(true.B)
      dut.io.M_AXI_ARVALID.expect(true.B)
      dut.io.M_AXI_ARADDR.expect("h0000_1000".U)

      // --- Step 2: 模擬 AXI 4-word Refill ---
      for (i <- 0 until 4) {
        // AR 握手
        dut.io.M_AXI_ARREADY.poke(true.B)
        while (dut.io.M_AXI_ARVALID.peekBoolean() == false) dut.clock.step()
        dut.clock.step()
        dut.io.M_AXI_ARREADY.poke(false.B)

        // 回傳資料
        dut.io.M_AXI_RDATA.poke((0x100 + i).U) // 模擬指令資料 0x100, 0x101...
        dut.io.M_AXI_RVALID.poke(true.B)
        dut.clock.step()
        dut.io.M_AXI_RVALID.poke(false.B)
      }

      // --- Step 3: 更新 Tag 後回到 Idle ---
      dut.clock.step(2) 
      dut.io.cpu_stall.expect(false.B) // Refill 完成，Stall 應該解除

      // --- Step 4: 再次請求同一個地址 (Hit) ---
      dut.io.cpu_addr.poke("h0000_1000".U)
      dut.clock.step()
      dut.io.cpu_stall.expect(false.B)
      dut.io.cpu_data.expect(0x100.U) // 應該讀到剛才 Refill 的第一筆資料
    }
  }
}
