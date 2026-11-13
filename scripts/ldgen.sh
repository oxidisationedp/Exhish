#!/bin/sh
D=${LDOUT:?}
mkdir -p "$D"
F=$D/loader-$1.ld
cat > "$F" <<LDEOF
PHDRS
{
text PT_LOAD FLAGS(5);
stack PT_GNU_STACK FLAGS(6);
}
SECTIONS
{
. = $1;
.text : { *(.text .text.*) } :text
.rodata : { *(.rodata .rodata.*) } :text
/DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
LDEOF
echo "$F"
