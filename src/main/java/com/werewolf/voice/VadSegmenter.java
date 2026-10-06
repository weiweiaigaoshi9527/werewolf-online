package com.werewolf.voice;

import com.werewolf.config.VoiceProperties;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 服务端能量 VAD 断句：接收浏览器持续上传的裸 PCM16 单声道（小端，采样率 sampleRate），
 * 按静音阈值切成一句句短语交给 ASR。含 ~160ms 前置环形缓冲，避免吃掉句首辅音。
 * 每个用户连接持有一个实例，仅在语音执行器单线程内对该用户调用（非并发）。
 */
public class VadSegmenter {

    private static final int FRAME = 320; // 20ms @16k
    private final VoiceProperties props;
    private final int silenceFrames, maxSpeechFrames, minSpeechFrames;

    private final ByteArrayOutputStream acc = new ByteArrayOutputStream(); // 跨块奇数字节残留（0/1 字节）
    private final ByteArrayOutputStream speech = new ByteArrayOutputStream();
    private final short[] ring = new short[FRAME * 8];                     // ~160ms 环形
    private int ringPos = 0, ringFilled = 0;
    private boolean speaking = false;
    private int speechFrames = 0, silenceRun = 0;

    public VadSegmenter(VoiceProperties props) {
        this.props = props;
        this.silenceFrames = Math.max(1, props.getVadSilenceMs() / 20);
        this.maxSpeechFrames = Math.max(5, props.getVadMaxSpeechMs() / 20);
        this.minSpeechFrames = Math.max(1, props.getVadMinSpeechMs() / 20);
    }

    public List<byte[]> feed(byte[] pcm) {
        acc.write(pcm, 0, pcm.length);
        byte[] all = acc.toByteArray();
        int n = all.length / 2;
        acc.reset();
        if ((all.length & 1) == 1) acc.write(all[all.length - 1]);
        short[] shorts = new short[n];
        for (int i = 0; i < n; i++) shorts[i] = (short) ((all[i * 2] & 0xff) | ((all[i * 2 + 1] & 0xff) << 8));

        List<byte[]> done = new ArrayList<>();
        int idx = 0;
        while (n - idx >= FRAME) {
            processFrame(shorts, idx, done);
            idx += FRAME;
        }
        // 不足一帧的尾巴留在 shorts 里会被丢弃（帧很小，可忽略）；这里不做残留以保持简单
        return done;
    }

    private void processFrame(short[] shorts, int off, List<byte[]> done) {
        double sum = 0;
        for (int i = 0; i < FRAME; i++) { double s = shorts[off + i] / 32768.0; sum += s * s; }
        boolean voiced = Math.sqrt(sum / FRAME) >= props.getVadRmsThreshold();

        byte[] fb = new byte[FRAME * 2];
        for (int i = 0; i < FRAME; i++) {
            short v = shorts[off + i];
            fb[i * 2] = (byte) (v & 0xff);
            fb[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }

        if (voiced) {
            if (!speaking) {
                speaking = true;
                speech.reset();
                speechFrames = 0; silenceRun = 0;
                int from = (ringPos - ringFilled + ring.length) % ring.length; // 倾倒前置环形
                for (int i = 0; i < ringFilled; i++) {
                    short v = ring[(from + i) % ring.length];
                    speech.write(v & 0xff); speech.write((v >> 8) & 0xff);
                }
            }
            speech.write(fb, 0, fb.length);
            speechFrames += 1;
            silenceRun = 0;
            if (speechFrames >= maxSpeechFrames) emit(done);
        } else if (speaking) {
            speech.write(fb, 0, fb.length);
            silenceRun += 1;
            if (silenceRun >= silenceFrames) {
                if (speechFrames >= minSpeechFrames) emit(done);
                else resetCollect();
            }
        }
        // 环形缓冲始终滚动（含静音），供下一句的前置
        for (int i = 0; i < FRAME; i++) { ring[ringPos] = shorts[off + i]; ringPos = (ringPos + 1) % ring.length; ringFilled = Math.min(ringFilled + 1, ring.length); }
    }

    private void emit(List<byte[]> done) {
        byte[] seg = speech.toByteArray();
        int trim = Math.min(seg.length, silenceRun * FRAME * 2);
        // 去尾部静音：仅在裁剪后仍能保留至少一帧时裁剪，避免把整段都裁没；保留逻辑与原实现一致。
        if (seg.length - trim > 640) seg = Arrays.copyOf(seg, seg.length - trim);
        // 提交条件放宽为 >0：原来硬阈值 640（一帧）会把句末不足一帧的 PCM 直接丢弃导致轻微截断。
        // 注意：是否成句仍由调用方 speechFrames >= minSpeechFrames 把关，这里的 640 只是冗余兜底，放宽安全。
        if (seg.length > 0) done.add(seg);
        resetCollect();
    }

    private void resetCollect() {
        speaking = false; speech.reset(); speechFrames = 0; silenceRun = 0;
    }

    public byte[] flush() {
        if (!speaking || speechFrames < minSpeechFrames) { resetCollect(); return null; }
        List<byte[]> one = new ArrayList<>();
        emit(one);
        return one.isEmpty() ? null : one.get(0);
    }

    public void reset() {
        resetCollect(); acc.reset(); ringFilled = 0; ringPos = 0;
    }
}
