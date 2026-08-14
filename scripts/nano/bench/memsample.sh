#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 PID OUT.CSV [INTERVAL_S]" >&2
    exit 2
}

[ "$#" -ge 2 ] && [ "$#" -le 3 ] || usage
pid=$1
output=$2
interval=${3:-1}

case "$pid" in
    ''|*[!0-9]*) usage ;;
esac
[ -r "/proc/$pid/status" ] || {
    echo "Cannot read /proc/$pid/status" >&2
    exit 1
}

if [ ! -s "$output" ]; then
    printf '%s\n' 'timestamp_epoch_s,VmRSS_kB,RssAnon_kB,RssFile_kB,RssShmem_kB,VmSwap_kB,VmHWM_kB,Pss_kB,Pss_Anon_kB,SwapUsed_kB' >>"$output"
fi

while [ -r "/proc/$pid/status" ]; do
    if [ -r "/proc/$pid/smaps_rollup" ]; then
        smaps="/proc/$pid/smaps_rollup"
        smaps_rollup=1
    else
        smaps="/proc/$pid/smaps"
        smaps_rollup=0
    fi
    timestamp=$(date +%s)

    awk -v timestamp="$timestamp" -v status="/proc/$pid/status" \
        -v smaps="$smaps" -v smaps_rollup="$smaps_rollup" '
        FILENAME == status && $1 == "VmRSS:"    { rss = $2 }
        FILENAME == status && $1 == "RssAnon:"  { anon = $2 }
        FILENAME == status && $1 == "RssFile:"  { file = $2 }
        FILENAME == status && $1 == "RssShmem:" { shmem = $2 }
        FILENAME == status && $1 == "VmSwap:"   { swap = $2 }
        FILENAME == status && $1 == "VmHWM:"    { hwm = $2 }
        FILENAME == smaps && $1 == "Pss:" {
            if (smaps_rollup) pss = $2
            else pss += $2
        }
        FILENAME == smaps && smaps_rollup && $1 == "Pss_Anon:" { pss_anon = $2 }
        FILENAME == smaps && !smaps_rollup && $1 == "Rss:" { smaps_rss += $2 }
        FILENAME == smaps && !smaps_rollup && $1 == "Anonymous:" { smaps_anon += $2 }
        FILENAME == "/proc/meminfo" && $1 == "SwapTotal:" { swap_total = $2 }
        FILENAME == "/proc/meminfo" && $1 == "SwapFree:"  { swap_free = $2 }
        END {
            if (!smaps_rollup) {
                anon = smaps_anon
                # On pre-4.5 kernels this approximation folds shmem into the file share.
                file = smaps_rss - smaps_anon
            }
            printf "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n", timestamp,
                rss, anon, file, shmem, swap, hwm, pss, pss_anon,
                swap_total - swap_free
        }
    ' "/proc/$pid/status" "$smaps" /proc/meminfo >>"$output" 2>/dev/null || break

    sleep "$interval"
done
