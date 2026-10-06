package com.werewolf.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音（同语种声纹中继）相关配置：两枚本地 Python 微服务地址、采样率、
 * 匿名伪装人格（头像/昵称）、每个座位固定 Kokoro 音色、AI 逐句发声的节奏参数、VAD 参数。
 * 前缀 voice.*，见 application.yml；后台保存的覆盖值存 app_setting.voice，启动时叠加回本 Bean。
 */
@Component
@ConfigurationProperties(prefix = "voice")
@JsonIgnoreProperties(ignoreUnknown = true)
public class VoiceProperties {

    /** 总开关：false 时语音链路完全停用，退回纯文字。 */
    private boolean enabled = false;
    /** 允许房主在房间开启语音/匿名模式。 */
    private boolean allowVoiceMode = true;

    private String asrUrl = "http://127.0.0.1:5001";
    private String ttsUrl = "http://127.0.0.1:5002";
    /** 上行 PCM 采样率（Hz），SenseVoice 训练用 16000。 */
    private int sampleRate = 16000;
    /** 单次 ASR/TTS HTTP 调用超时（秒）。 */
    private int serviceTimeoutSeconds = 20;

    /** 每座位固定 Kokoro 音色池；座位号按 idx = (seat-1) % voices.size 取用，AI/真人都用同一座位音色以隐藏身份。 */
    private List<String> voices = List.of(
            "zf_xiaobei", "zm_yunjian", "zf_xiaoxiao", "zm_yunxi",
            "zf_xiaoni", "zm_yunyang", "zf_xiaoyi", "zm_yunxia");

    /* ---------- AI / 中继 逐句发声节奏 ---------- */
    /** 每个汉字的自然朗读时长（毫秒），用于估算句间停顿与总时长上限。 */
    private int msPerChar = 205;   // 实测 Kokoro 中文 ~267ms/字（短句更慢）；205+900ms 头部+分句间隙拟合误差 <15%
    /** 句间“思考”随机停顿下界（毫秒）。 */
    private int gapMinMs = 120;
    /** 句间“思考”随机停顿上界（毫秒）。 */
    private int gapMaxMs = 240;
    /** 开口前的起手随机延迟下界（毫秒）。 */
    private int leadInMinMs = 50;
    /** 开口前的起手随机延迟上界（毫秒）。 */
    private int leadInMaxMs = 180;
    /** 单条发言最多输出句数（防止过长）。 */
    private int maxSentencesPerTurn = 40;   // 16 句对长发言不够，超出的整段丢失
    /** 单条发言文本长度上限（字符）。 */
    private int maxSpeechChars = 600;   // 320 会把长发言的朗读截断（“没说完就被截断”）

    /* ---------- VAD（服务端能量断句） ---------- */
    /** 判定为人声的帧 RMS 阈值（0..1 归一化，作用于 16bit PCM）。 */
    private double vadRmsThreshold = 0.02;
    /** 连续静音多少毫秒切一句。 */
    private int vadSilenceMs = 750;
    /** 单句最长多少毫秒强制切（防不换气）。 */
    private int vadMaxSpeechMs = 9000;
    /** 静音预热：不足多少毫秒的语音段丢弃（去噪/爆破音）。 */
    private int vadMinSpeechMs = 350;

    /* ---------- 匿名伪装 ---------- */
    /** 匿名模式下对外的固定昵称池（座位 idx 取用）。 */
    private List<String> anonNames = List.of(
            "1号", "2号", "3号", "4号", "5号", "6号", "7号", "8号",
            "9号", "10号", "11号", "12号", "13号", "14号", "15号", "16号");
    /** 匿名模式下对外的固定头像 id 池。 */
    private List<Integer> anonAvatars = List.of(
            5, 12, 3, 9, 15, 6, 2, 11, 4, 8, 13, 1, 10, 14, 7, 16);
    /** 匿名模式下统一的昵称色 / 头像框色（消除个性差异）。 */
    private String anonNickColor = "#c7cbd6";
    private String anonFrameColor = "#2a2f3a";

    /* getters / setters */
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAllowVoiceMode() { return allowVoiceMode; }
    public void setAllowVoiceMode(boolean v) { this.allowVoiceMode = v; }
    public String getAsrUrl() { return asrUrl; }
    public void setAsrUrl(String v) { this.asrUrl = v; }
    public String getTtsUrl() { return ttsUrl; }
    public void setTtsUrl(String v) { this.ttsUrl = v; }
    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int v) { this.sampleRate = v; }
    public int getServiceTimeoutSeconds() { return serviceTimeoutSeconds; }
    public void setServiceTimeoutSeconds(int v) { this.serviceTimeoutSeconds = v; }
    public List<String> getVoices() { return voices; }
    public void setVoices(List<String> v) { this.voices = v; }
    public int getMsPerChar() { return msPerChar; }
    public void setMsPerChar(int v) { this.msPerChar = v; }
    public int getGapMinMs() { return gapMinMs; }
    public void setGapMinMs(int v) { this.gapMinMs = v; }
    public int getGapMaxMs() { return gapMaxMs; }
    public void setGapMaxMs(int v) { this.gapMaxMs = v; }
    public int getLeadInMinMs() { return leadInMinMs; }
    public void setLeadInMinMs(int v) { this.leadInMinMs = v; }
    public int getLeadInMaxMs() { return leadInMaxMs; }
    public void setLeadInMaxMs(int v) { this.leadInMaxMs = v; }
    public int getMaxSentencesPerTurn() { return maxSentencesPerTurn; }
    public void setMaxSentencesPerTurn(int v) { this.maxSentencesPerTurn = v; }
    public int getMaxSpeechChars() { return maxSpeechChars; }
    public void setMaxSpeechChars(int v) { this.maxSpeechChars = v; }
    public double getVadRmsThreshold() { return vadRmsThreshold; }
    public void setVadRmsThreshold(double v) { this.vadRmsThreshold = v; }
    public int getVadSilenceMs() { return vadSilenceMs; }
    public void setVadSilenceMs(int v) { this.vadSilenceMs = v; }
    public int getVadMaxSpeechMs() { return vadMaxSpeechMs; }
    public void setVadMaxSpeechMs(int v) { this.vadMaxSpeechMs = v; }
    public int getVadMinSpeechMs() { return vadMinSpeechMs; }
    public void setVadMinSpeechMs(int v) { this.vadMinSpeechMs = v; }
    public List<String> getAnonNames() { return anonNames; }
    public void setAnonNames(List<String> v) { this.anonNames = v; }
    public List<Integer> getAnonAvatars() { return anonAvatars; }
    public void setAnonAvatars(List<Integer> v) { this.anonAvatars = v; }
    public String getAnonNickColor() { return anonNickColor; }
    public void setAnonNickColor(String v) { this.anonNickColor = v; }
    public String getAnonFrameColor() { return anonFrameColor; }
    public void setAnonFrameColor(String v) { this.anonFrameColor = v; }

    /** 座位 → 固定音色 id。 */
    public String voiceForSeat(int seat) { return voiceForSeat(seat, null); }

    /** 座位 → 固定 Kokoro 音色 id（gender=M 取 zm_ 男声、F 取 zf_ 女声、null 用全部）。 */
    public String voiceForSeat(int seat, String gender) {
        List<String> pool = voices == null ? new ArrayList<>() : voices;
        if (gender != null && !gender.isBlank()) {
            String pref = "M".equalsIgnoreCase(gender) ? "zm_" : "zf_";
            List<String> g = new ArrayList<>();
            for (String v : pool) if (v != null && v.startsWith(pref)) g.add(v);
            if (!g.isEmpty()) pool = g;
        }
        if (pool.isEmpty()) return "zf_xiaoxiao";
        return pool.get(Math.floorMod(seat - 1, pool.size()));
    }
    /** 座位 → 匿名昵称。池内按取模使用；座位号超出昵称池（如 17~24 号大板）时回退为“N号”，避免与低位座位重名。 */
    public String anonName(int seat) {
        List<String> n = anonNames;
        if (n == null || n.isEmpty()) return seat + "号";
        if (seat > n.size()) return seat + "号";
        return n.get(Math.floorMod(seat - 1, n.size()));
    }
    /** 座位 → 匿名头像 id。 */
    public int anonAvatar(int seat) {
        List<Integer> a = anonAvatars;
        return (a == null || a.isEmpty()) ? 1 : a.get(Math.floorMod(seat - 1, a.size()));
    }
}
