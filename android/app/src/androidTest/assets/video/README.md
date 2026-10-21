Generated test fixture (no third-party footage):

ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -t 16 -c:v libx264 -preset veryfast -g 24 -b:v 1300k -minrate 1300k -maxrate 1300k -bufsize 2600k -x264-params nal-hrd=cbr:force-cfr=1 -pix_fmt yuv420p -c:a aac -b:a 64k generated-h264-aac.mp4

VideoStreamingDeviceTest inserts a virtual `free` atom immediately before the original tail `moov`.
Chunk offsets into the existing mdat remain valid. This tests real MP4 tail seeking above 100 MiB
without committing a huge binary. The fixture server is test-only device loopback.
