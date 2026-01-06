OUTPUT_ARCH("riscv")
ENTRY(_start)

SECTIONS
{
  . = 0x00001000;
  .text : { *(.text.init) *(.text.startup) *(.text) }
  .tohost : { *(.tohost) }
  .data ALIGN(0x1000) : { *(.data*) *(.rodata*) *(.sdata*) }
  .data.string : { *(.data.string)}
  .bss : { *(.bss) }
  _end = .;
}
