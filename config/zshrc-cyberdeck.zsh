# cyberdeck: log typed commands for the launcher panel
[ -r /usr/local/share/cyberdeck-cmdlog.sh ] && . /usr/local/share/cyberdeck-cmdlog.sh
# >>> cyberdeck shell pack >>> (strip: delete this whole block)
# ghosted autosuggestions
ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=#556680'
# fzf fuzzy search (Ctrl-R history, Ctrl-T files) in deck colors
source /usr/share/doc/fzf/examples/key-bindings.zsh 2>/dev/null
export FZF_DEFAULT_OPTS='--color=bg+:#1b2740,fg:#c5d1e6,hl:#367bf0,fg+:#eaf2ff,hl+:#5fd7ff,prompt:#367bf0,pointer:#5fd7ff,marker:#5cc48d,border:#367bf0'
# themed ls output
export LS_COLORS='di=1;38;2;95;215;255:ex=1;38;2;92;196;141:ln=38;2;138;110;240:fi=38;2;197;209;230'
alias ls='ls --color=auto'
alias ll='ls -lah --color=auto'
alias grep='grep --color=auto'
# login banner (strip: remove this if-block)
if [[ -o login ]]; then
  print -P "%F{#367bf0}"
  figlet -f small CYBERDECK 2>/dev/null | sed 's/^/ /'
  print -P " %F{#5fd7ff}>_ deck online%f  %F{#3d5468}$(uname -r)%f\n"
fi
# syntax highlighting - MUST be last
typeset -A ZSH_HIGHLIGHT_STYLES
ZSH_HIGHLIGHT_STYLES[command]='fg=#5cc48d'
ZSH_HIGHLIGHT_STYLES[builtin]='fg=#5cc48d'
ZSH_HIGHLIGHT_STYLES[alias]='fg=#5cc48d'
ZSH_HIGHLIGHT_STYLES[function]='fg=#5cc48d'
ZSH_HIGHLIGHT_STYLES[unknown-token]='fg=#c9342f,bold'
ZSH_HIGHLIGHT_STYLES[path]='fg=#5fd7ff'
ZSH_HIGHLIGHT_STYLES[single-quoted-argument]='fg=#e0a445'
ZSH_HIGHLIGHT_STYLES[double-quoted-argument]='fg=#e0a445'
ZSH_HIGHLIGHT_STYLES[globbing]='fg=#8a6ef0'
# <<< cyberdeck shell pack <<<
# >>> cyberdeck completion menu >>> (strip: delete this block)
# Tab shows a navigable, highlighted list below the line
zmodload zsh/complist 2>/dev/null
zstyle ':completion:*' menu select
zstyle ':completion:*' list-colors "${(s.:.)LS_COLORS}"
zstyle ':completion:*:descriptions' format '%F{#367bf0}%d%f'
zstyle ':completion:*' group-name ''
zstyle ':completion:*' completer _complete _match _approximate
# arrow keys + tab navigate the menu; enter selects
bindkey -M menuselect '^[[Z' reverse-menu-complete 2>/dev/null
# <<< cyberdeck completion menu <<<
