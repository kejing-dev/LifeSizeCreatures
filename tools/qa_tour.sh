#!/usr/bin/env bash
# One pass over the design states with the debug broadcast; screenshots to $OUT (default .scratch/tour).
# Each state is shot twice ~4 s apart (the emulator sometimes returns the previous frame); use the second.
#   tools/qa_tour.sh [state ...]      default: all of S0 S13 S1 S2 S3 S4 S5 S6 S6b S7 S8 S8x S9 S10 S11a S11b S12
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D="$ROOT/tools/dev.sh"
OUT="${OUT:-$ROOT/.scratch/tour}"
mkdir -p "$OUT"
shot() { SHOTS="$OUT" "$D" shot "$1_a" >/dev/null; sleep 4; SHOTS="$OUT" "$D" shot "$1" >/dev/null; rm -f "$OUT/$1_a.png" "$OUT/$1_a_half.png"; echo "shot $1"; }
cmd() { "$D" cmd "$@" >/dev/null; }
fresh() { "$D" stop; sleep 1; "$D" start >/dev/null; sleep 25; cmd freeze 0; cmd lang "${1:-zh}"; }
wait_s() { sleep "${1:-8}"; }

run() {
  case "$1" in
    S0) fresh; shot S0 ;;
    S13) fresh; cmd room S13; wait_s 4; shot S13; cmd freeze 0; cmd room S0; wait_s 2 ;;
    S1) cmd room S1; cmd freeze 1; wait_s 8; shot S1; cmd freeze 0 ;;
    S2) cmd room S2; wait_s 6; shot S2 ;;
    S3) cmd room S2; wait_s 3; cmd tile horse; cmd ghost 2.6; wait_s 6; shot S3; cmd ghostoff; cmd tile horse ;;
    S4) cmd room S4; wait_s 14; shot S4 ;;
    S5) cmd clear; cmd place shiba,1.8,0; wait_s 10; cmd freeze 1; cmd card 0; wait_s 8; shot S5; cmd close; cmd freeze 0 ;;
    S6) cmd clear; cmd place trex,6,3; wait_s 14; shot S6 ;;
    S6b) cmd clear; cmd place triceratops,5,2.5; wait_s 14; shot S6b ;;
    S7) cmd clear; cmd place trex,6,3; wait_s 14; cmd freeze 1; cmd card 0; wait_s 2; cmd scale fit; wait_s 8; shot S7; cmd close; cmd freeze 0 ;;
    S8) cmd room S8; wait_s 16; shot S8 ;;
    S8x) cmd room S8; wait_s 14; cmd freeze 1; cmd place wolf,2.4,1.5; wait_s 8; shot S8x; cmd freeze 0 ;;
    S9) cmd clear; cmd place horse,2.6,0.8; wait_s 12; cmd freeze 1; cmd drag 0,0.8,0,6000; wait_s 4; shot S9; cmd freeze 0 ;;
    S10) cmd room S10; wait_s 6; shot S10 ;;
    S11a) cmd lang en; cmd room S2; wait_s 6; shot S11a ;;
    S11b) cmd lang en; cmd clear; cmd place shiba,1.8,0; wait_s 10; cmd freeze 1; cmd card 0; wait_s 8; shot S11b; cmd close; cmd freeze 0; cmd lang zh ;;
    S12) cmd clear; cmd slowload 1; cmd place stag,2.8,1.2; wait_s 1; SHOTS="$OUT" "$D" shot S12 >/dev/null; echo "shot S12"; cmd slowload 0 ;;
  esac
}

if [ $# -eq 0 ]; then set -- S0 S13 S1 S2 S3 S4 S5 S6 S6b S7 S8 S8x S9 S10 S11a S11b S12; fi
for s in "$@"; do run "$s"; done
