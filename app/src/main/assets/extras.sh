
export PATH=/etc/exhish/bin:$PATH
mkdir -p "$HOME/.exhish" 2>/dev/null

. /etc/exhish/apply.sh
exh_load

exh_chk() {
  local m
  m=$(stat -c %Y "$HOME/.exhish/config" /etc/hostname 2>/dev/null | tr '\n' ' ')
  if [ "$m" != "$EXH_STAMP" ]; then
    [ -n "$EXH_STAMP" ] && exh_load
    EXH_STAMP="$m"
  fi
}
EXH_STAMP=$(stat -c %Y "$HOME/.exhish/config" /etc/hostname 2>/dev/null | tr '\n' ' ')
PROMPT_COMMAND="exh_chk; ${PROMPT_COMMAND}"
