package com.werewolf.voice;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 语音通道二进制帧封装：[4 字节大端 metaLen][metaLen 字节 UTF-8 JSON 元信息][裸音频负载]。
 * 下行 TTS 音频用它携带是谁、第几句；上行麦克风帧直接用裸 PCM16（服务端按连接识别发送者），不经此封装。
 */
public final class AudioEnvelope {

    private AudioEnvelope() {}

    public static byte[] encode(String metaJson, byte[] payload) {
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        int pLen = payload == null ? 0 : payload.length;
        ByteBuffer bb = ByteBuffer.allocate(4 + meta.length + pLen);
        bb.putInt(meta.length);
        bb.put(meta);
        if (payload != null) bb.put(payload);
        return bb.array();
    }

    public static Decoded decode(byte[] frame) {
        if (frame == null || frame.length < 4) return new Decoded("", new byte[0]);
        ByteBuffer bb = ByteBuffer.wrap(frame);
        int metaLen = bb.getInt();
        if (metaLen < 0 || 4 + metaLen > frame.length) return new Decoded("", new byte[0]);
        byte[] meta = new byte[metaLen];
        bb.get(meta);
        byte[] payload = new byte[bb.remaining()];
        bb.get(payload);
        return new Decoded(new String(meta, StandardCharsets.UTF_8), payload);
    }

    public record Decoded(String metaJson, byte[] payload) {}
}
