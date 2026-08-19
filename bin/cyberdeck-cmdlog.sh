# Cyberdeck command log - sourced from the interactive shell rc files.
#
# The launcher used to show processes sampled out of /proc, which can never see
# what you actually type: `pwd`, `cd`, `echo` and friends are shell BUILTINS and
# fork nothing at all, and anything short-lived falls between samples anyway.
# The shell is the only thing that knows a command was run, so ask it.
#
# Writes to tmpfs, so this costs the SD card nothing.

CYBERDECK_CMDLOG=/dev/shm/cyberdeck-cmdlog

cyberdeck_cmdlog_write() {
    [ -n "${1:-}" ] || return 0
    case "$1" in
        cyberdeck_cmdlog*|*CYBERDECK_CMDLOG*) return 0 ;;   # never log ourselves
    esac
    # bound it; truncating a tmpfs file is free
    if [ -f "$CYBERDECK_CMDLOG" ]; then
        sz=$(stat -c%s "$CYBERDECK_CMDLOG" 2>/dev/null || echo 0)
        [ "$sz" -gt 32768 ] && : > "$CYBERDECK_CMDLOG"
    fi
    printf '%s\n' "$1" >> "$CYBERDECK_CMDLOG" 2>/dev/null
    chmod 666 "$CYBERDECK_CMDLOG" 2>/dev/null
    return 0
}

if [ -n "${ZSH_VERSION:-}" ]; then
    # add-zsh-hook composes with whatever else already hooks preexec, instead of
    # clobbering it the way defining preexec() directly would.
    autoload -Uz add-zsh-hook 2>/dev/null
    cyberdeck_preexec() { cyberdeck_cmdlog_write "$1"; }
    add-zsh-hook preexec cyberdeck_preexec 2>/dev/null
elif [ -n "${BASH_VERSION:-}" ]; then
    cyberdeck_bash_hook() {
        [ -n "$COMP_LINE" ] && return 0          # not during completion
        [ "$BASH_COMMAND" = "$PROMPT_COMMAND" ] && return 0
        cyberdeck_cmdlog_write "$BASH_COMMAND"
    }
    trap 'cyberdeck_bash_hook' DEBUG
fi
