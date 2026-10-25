"""Backport upstream MP4 channel-count emission; never patch an output file.

Reference: FFmpeg/FFmpeg n6.0, libavformat/movenc.c, mov_write_audio_tag.
The upstream single-thread Wasm CLI still requires the n5.1.4 ABI.
"""
from pathlib import Path

path = Path("libavformat/movenc.c")
source = path.read_text()
old = """            if (track->par->codec_id == AV_CODEC_ID_FLAC ||
                track->par->codec_id == AV_CODEC_ID_ALAC ||
                track->par->codec_id == AV_CODEC_ID_OPUS) {
                avio_wb16(pb, track->par->ch_layout.nb_channels);
            } else {
                avio_wb16(pb, 2);
            }"""
if source.count(old) != 1:
    raise SystemExit("Expected unique upstream channel-count block")
path.write_text(source.replace(old, "            avio_wb16(pb, track->par->ch_layout.nb_channels);"))
