package com.aiqiepian.service;

import com.aiqiepian.entity.Clip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能标题生成（FR-P5-05）。
 * 比赛切片：直播间名 +【战队1】VS【战队2】+ 时刻后缀（战队名从官方直播标题解析）；
 * 主播日常切片：主播精简名 + 高频弹幕汇总成一句话（数据来源 clip.top_danmaku，FR-P4-06）；
 * 无弹幕统计时回退通用模板。
 * 接入本地大模型（ai_mode=local）时：弹幕摘要、投稿简介优先用模型生成，失败静默回退纯规则。
 */
@Component
public class TitleGenerator {

    public record Result(String title, String kind, String summary) {
    }

    /** B站稿件标题上限 */
    private static final int MAX_LEN = 79;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 可选：本地大模型增强，未启用/不可用时全部回退规则实现 */
    private final LocalLlmClient llm;

    public TitleGenerator(LocalLlmClient llm) {
        this.llm = llm;
    }

    /** 战队名：中英文数字点横杠中点，2-12 字符 */
    private static final Pattern VS = Pattern.compile(
            "([0-9A-Za-z\\u4e00-\\u9fa5·.\\-]{2,12})\\s*(?:VS|vs|Vs|対战|对战|对阵)\\s*([0-9A-Za-z\\u4e00-\\u9fa5·.\\-]{2,12})");
    /** 赛事语境词（命中其一且能解析出 VS 双方才判为比赛切片） */
    private static final Pattern ESPORTS_CTX = Pattern.compile(
            "赛|杯|联赛|季后赛|小组赛|总决赛|预选|常规赛|挑战赛| masters|MSL|KPL|LPL|PEL|LEC|VCS|IEM|PGL|TI|DMC");
    /** 主播名里的营销尾巴（长词优先，避免"官方直播间"只剥掉后半截） */
    private static final Pattern ANCHOR_NOISE = Pattern.compile(
            "(官方直播间|官方|录播|回放|直播中|房间号|直播间|[:：|｜].*)$");

    public Result suggest(Clip clip) {
        return suggest(clip, true);
    }

    /** allowLlm=false 时走纯规则（不等本地模型），供建作品即时返回；异步补全时再传 true 精修 */
    public Result suggest(Clip clip, boolean allowLlm) {
        String liveTitle = nz(clip.getRoomName());
        if (liveTitle.isEmpty()) liveTitle = "直播切片";
        Matcher vs = VS.matcher(liveTitle);
        if (vs.find() && ESPORTS_CTX.matcher(liveTitle).find()) {
            String t1 = cleanTeam(vs.group(1));
            String t2 = cleanTeam(vs.group(2));
            String moment = momentSuffix(clip);
            String base = trim(liveTitle.replace(vs.group(0), " "));
            String title = clamp(base + "【" + t1 + "】VS【" + t2 + "】" + moment);
            return new Result(title, "esports", "比赛切片：从直播标题解析出对阵双方");
        }
        // 非比赛切片：优先用本地大模型结合"看点 + 弹幕 + 精彩度"写一个爆款标题（daily/generic 都适用）
        if (allowLlm && llm.available()) {
            String ai = catchyTitle(clip, liveTitle);
            if (ai != null && !ai.isBlank()) {
                return new Result(clamp(ai), "ai", "本地大模型结合看点与弹幕生成");
            }
        }
        String sentence = danmakuSentence(topDanmakuWords(clip.getTopDanmaku(), 5));
        if (sentence != null) {
            String anchor = shortAnchor(clip.getAnchorName(), liveTitle);
            return new Result(clamp(anchor + "高能瞬间，" + sentence), "daily", sentence);
        }
        String hook = momentSuffix(clip);
        String fallback = trim(liveTitle) + " " + hook + "名场面";
        return new Result(clamp(fallback), "generic", "未采集到弹幕统计，使用通用模板");
    }

    /** 本地大模型爆款标题：只喂干净的语境（主播/主题 + 精彩度情绪 + 观众弹幕热词），
     *  不喂技术化的 ai_reason（小模型会原样复读"响度/场景切换"等词），失败返回 null */
    private String catchyTitle(Clip clip, String liveTitle) {
        List<String> words = topDanmakuWords(clip.getTopDanmaku(), 8);
        int score = clip.getScore() == null ? 0 : clip.getScore();
        String mood = score >= 90 ? "天秀高光" : score >= 80 ? "精彩操作" : score >= 70 ? "高能瞬间" : "看点片段";
        String subject = shortAnchor(clip.getAnchorName(), liveTitle);
        String sys = "你是B站直播切片标题写手。为这位主播/这个主题的直播精彩片段写一个不超过16字的中文爆款标题："
                + "口语化、有情绪或悬念（如 这波、直接、看呆、破防、天秀、翻车、1打5、逆风翻盘 等），贴合该游戏或直播主题；"
                + "不要出现响度、能量、场景、帧、密度、弹幕、评分、精彩度等技术或数据词；"
                + "不要书名号、引号、日期、编号、话题标签；只输出标题本身，不要解释。";
        StringBuilder u = new StringBuilder();
        u.append("主播/主题：").append(subject).append('\n');
        u.append("片段类型：").append(mood).append('\n');
        if (!words.isEmpty()) u.append("观众常刷的词：").append(String.join("、", words)).append('\n');
        return llm.oneLine(sys, u.toString(), 60, 30, 16);
    }

    /** 投稿简介生成：ai_mode=local 时让模型写一段简介；不喂技术化 ai_reason（小模型会复读），失败返回 null */
    public String generateDescription(Clip clip, String title) {
        if (!llm.available()) return null;
        List<String> words = topDanmakuWords(clip.getTopDanmaku(), 6);
        int score = clip.getScore() == null ? 0 : clip.getScore();
        String mood = score >= 90 ? "天秀高光名场面" : score >= 80 ? "精彩操作" : score >= 70 ? "高能瞬间" : "值得一看";
        String subject = shortAnchor(clip.getAnchorName(), nz(clip.getRoomName()));
        String sys = "你是B站投稿文案助手。为一支直播切片写一段不超过60字的视频简介：自然、口语化、吸引人，"
                + "可结合主播/游戏主题与观众反应；不要出现响度、能量、场景切换、帧、密度、弹幕、评分、精彩度等技术或数据词，"
                + "不要引号、书名号、日期、编号、话题标签；只输出简介本身。";
        StringBuilder facts = new StringBuilder();
        facts.append("标题：").append(nz(title)).append('\n');
        facts.append("主播/主题：").append(subject).append('\n');
        facts.append("片段类型：").append(mood).append('\n');
        if (!words.isEmpty()) facts.append("观众常刷的词：").append(String.join("、", words));
        return llm.oneLine(sys, facts.toString(), 160, 40, 120);
    }

    /** 解析 top_danmaku JSON，返回前 k 个高频文本（c≥1，长度≤12） */
    private static List<String> topDanmakuWords(String topDanmakuJson, int k) {
        List<String> words = new ArrayList<>();
        if (topDanmakuJson == null || topDanmakuJson.isBlank()) return words;
        try {
            JsonNode arr = MAPPER.readTree(topDanmakuJson);
            if (!arr.isArray()) return words;
            for (JsonNode n : arr) {
                int c = n.path("c").asInt(0);
                String t = n.path("t").asText("").trim();
                if (c >= 1 && !t.isEmpty() && t.length() <= 12) words.add(t);
                if (words.size() >= k) break;
            }
        } catch (Exception ignored) {
        }
        return words;
    }

    /** 高频弹幕 Top 词 → 一句话（前 3 条；1 条写"满屏"，多条写"弹幕齐刷"） */
    private static String danmakuSentence(List<String> all) {
        if (all.isEmpty()) return null;
        List<String> words = new ArrayList<>();
        for (String w : all) {
            words.add(w);
            if (words.size() >= 3) break;
        }
        if (words.size() == 1) return "满屏「" + words.get(0) + "」";
        StringBuilder sb = new StringBuilder("弹幕齐刷");
        for (String w : words) sb.append("「").append(w).append("」");
        return sb.toString();
    }

    private static String momentSuffix(Clip clip) {
        int score = clip.getScore() == null ? 0 : clip.getScore();
        if (score >= 90) return "决胜时刻";
        String reason = nz(clip.getAiReason());
        if (reason.contains("场景切换")) return "团战名场面";
        if (score >= 80) return "关键一波";
        return "精彩对决";
    }

    private static String cleanTeam(String raw) {
        String t = raw.replaceAll("[【】\\[\\]()（）]", "").trim();
        return t.length() > 10 ? t.substring(0, 10) : t;
    }

    private static String shortAnchor(String anchor, String liveTitle) {
        String a = anchor != null && !anchor.isBlank() ? anchor.trim() : liveTitle;
        a = ANCHOR_NOISE.matcher(a).replaceAll("").trim();
        int sp = a.indexOf(' ');
        if (sp > 1) a = a.substring(0, sp);
        if (a.length() > 8) a = a.substring(0, 8);
        return a.isEmpty() ? "主播" : a;
    }

    private static String trim(String s) {
        String t = s.replaceAll("[【】\\[\\]]", "").replaceAll("\\s{2,}", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static String clamp(String s) {
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
