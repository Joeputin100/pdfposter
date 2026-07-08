#!/usr/bin/env bash
# rc83: encode the rendered frame sequence + mix the eleven_v3 narration
# over the music bed. VO delays are timed to the segment boundaries in
# render-frames.js (title 0 / pick 5.8 / size 8.4 / assy 12.1 / models 19.1 /
# compare 24.3 / gemini 28.3 / free 31.1 / end 37.9; +250ms speech lead).
set -euo pipefail
cd "$(dirname "$0")"
ffmpeg -y -loglevel error -framerate 30 -i 'frames/f%04d.jpg' \
 -i audio/music-bed3.m4a \
 -i audio/vo-1-title.mp3 -i audio/vo-2-pick.mp3 -i audio/vo-3-size.mp3 -i audio/vo-4-assy.mp3 \
 -i audio/vo-5-models.mp3 -i audio/vo-6-compare.mp3 -i audio/vo-7-gemini.mp3 -i audio/vo-9-free.mp3 -i audio/vo-8-end.mp3 \
 -filter_complex "[1:a]volume=0.26[music];\
[2:a]adelay=250|250[a1];[3:a]adelay=6050|6050[a2];[4:a]adelay=8650|8650[a3];\
[5:a]adelay=12350|12350[a4];[6:a]adelay=19350|19350[a5];[7:a]adelay=24550|24550[a6];\
[8:a]adelay=28550|28550[a7];[9:a]adelay=31350|31350[a8];[10:a]adelay=38150|38150[a9];\
[music][a1][a2][a3][a4][a5][a6][a7][a8][a9]amix=inputs=10:duration=first:normalize=0[aout]" \
 -map 0:v -map "[aout]" -c:v libx264 -pix_fmt yuv420p -preset slow -crf 19 \
 -c:a aac -b:a 192k -shortest product-video.mp4
ffprobe -v error -show_entries format=duration,size -of csv=p=0 product-video.mp4
