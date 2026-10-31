
exh_theme_data() {
  case "$1" in
    dracula)   echo "#282a36 #f8f8f2 #f8f8f2 #21222c #ff5555 #50fa7b #f1fa8c #bd93f9 #ff79c6 #8be9fd #f8f8f2 #6272a4 #ff6e6e #69ff94 #ffffa5 #d6acff #ff92df #a4ffff #ffffff" ;;
    nord)      echo "#2e3440 #d8dee9 #d8dee9 #3b4252 #bf616a #a3be8c #ebcb8b #81a1c1 #b48ead #88c0d0 #e5e9f0 #4c566a #bf616a #a3be8c #ebcb8b #81a1c1 #b48ead #8fbcbb #eceff4" ;;
    solarized) echo "#002b36 #839496 #93a1a1 #073642 #dc322f #859900 #b58900 #268bd2 #d33682 #2aa198 #eee8d5 #002b36 #cb4b16 #586e75 #657b83 #839496 #6c71c4 #93a1a1 #fdf6e3" ;;
    gruvbox)   echo "#282828 #ebdbb2 #ebdbb2 #282828 #cc241d #98971a #d79921 #458588 #b16286 #689d6a #a89984 #928374 #fb4934 #b8bb26 #fabd2f #83a598 #d3869b #8ec07c #ebdbb2" ;;
    monokai)   echo "#272822 #f8f8f2 #f8f8f2 #272822 #f92672 #a6e22e #f4bf75 #66d9ef #ae81ff #a1efe4 #f8f8f2 #75715e #f92672 #a6e22e #f4bf75 #66d9ef #ae81ff #a1efe4 #f9f8f5" ;;
    tokyo)     echo "#1a1b26 #c0caf5 #c0caf5 #15161e #f7768e #9ece6a #e0af68 #7aa2f7 #bb9af7 #7dcfff #a9b1d6 #414868 #f7768e #9ece6a #e0af68 #7aa2f7 #bb9af7 #7dcfff #c0caf5" ;;
    matrix)    echo "#000000 #00ff41 #00ff41 #000000 #008f11 #00ff41 #8bff8b #006400 #00b32d #00ff9c #c8ffc8 #0d3b0d #00a82d #39ff14 #b6ff9e #2e8b57 #00cc66 #7fffd4 #ffffff" ;;
    paper)     echo "#fafafa #2b2b2b #2b2b2b #000000 #c91b00 #008a00 #9a8f00 #0225c7 #c930c7 #008b8d #7a7a7a #686868 #ff6e67 #1fb83a #b5a700 #4b5cff #ff77ff #00a0a2 #444444" ;;
    *) echo "" ;;
  esac
}

exh_themes="default dracula nord solarized gruvbox monokai tokyo matrix paper"

exh_osc() { printf '\033]%s\007' "$1"; }

exh_apply() {
  local d i=0 c
  d=$(exh_theme_data "${EXH_THEME:-default}")
  if [ -z "$d" ]; then
    printf '\033]104\007\033]110\007\033]111\007\033]112\007'
  else
    set -- $d
    exh_osc "11;$1"; exh_osc "10;$2"; exh_osc "12;$3"
    shift 3
    for c in "$@"; do
      exh_osc "4;$i;$c"
      i=$((i + 1))
    done
  fi
  [ -n "$EXH_BG" ] && exh_osc "11;$EXH_BG"
  [ -n "$EXH_FG" ] && exh_osc "10;$EXH_FG"
  [ -n "$EXH_CUR" ] && exh_osc "12;$EXH_CUR"
  return 0
}

exh_load() {
  unset EXH_THEME EXH_BG EXH_FG EXH_CUR EXH_HC EXH_MOTD EXH_FONT
  [ -f "$HOME/.exhish/config" ] && . "$HOME/.exhish/config"
  exh_apply
  _h=$(head -n1 /etc/hostname 2>/dev/null)
  HOSTNAME=${_h:-localhost}
  unset _h
}
