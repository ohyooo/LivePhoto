# Rebuild the pinned ffmpeg.wasm engine, retaining its toolchain/CLI ABI.
FROM emscripten/emsdk:3.1.40 AS build
ENV INSTALL_DIR=/opt FFMPEG_ST=yes
ENV CFLAGS="-I/opt/include -O3" CXXFLAGS="-I/opt/include -O3" LDFLAGS="-L/opt/lib -O3"
ENV EM_PKG_CONFIG_PATH=/opt/lib/pkgconfig
RUN apt-get update && apt-get install -y --no-install-recommends git pkg-config ca-certificates python3 && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch v12.15 https://github.com/ffmpegwasm/ffmpeg.wasm.git /wrapper && \
    git clone --depth 1 --branch n5.1.4 https://github.com/FFmpeg/FFmpeg.git /ffmpeg && \
    git clone --depth 1 --branch 4-cores https://github.com/ffmpegwasm/x264.git /x264 && \
    test "$(git -C /x264 rev-parse HEAD)" = 33cac6b77d5b9259c552156013a817ab23119612
WORKDIR /x264
RUN bash /wrapper/build/x264.sh
WORKDIR /ffmpeg
COPY preserve-channels.py /preserve-channels.py
RUN python3 /preserve-channels.py
# Audio is copied, never encoded. Keep the upstream decoders/filters; only the
# already exposed AVC/JPEG encoders and null-output decode verification adapters
# are needed. No compressed audio encoder is available.
RUN bash /wrapper/build/ffmpeg.sh --enable-gpl --enable-libx264 --disable-encoders --enable-encoder=libx264,mjpeg,wrapped_avframe,pcm_s16le
RUN mkdir -p src /out && cp -R /wrapper/src/bind /wrapper/src/fftools src/ && \
    bash /wrapper/build/ffmpeg-wasm.sh -lx264 -sEXPORT_ES6 -o /out/ffmpeg-core.js && \
    printf '{"ffmpeg":"%s","wrapper":"%s","x264":"%s","channelCount":"FFmpeg n6.0 movenc.c backport","compressedAudioEncoders":false}\n' \
      "$(git rev-parse HEAD)" "$(git -C /wrapper rev-parse HEAD)" "$(git -C /x264 rev-parse HEAD)" > /out/build.json
FROM scratch AS export
COPY --from=build /out/ /
