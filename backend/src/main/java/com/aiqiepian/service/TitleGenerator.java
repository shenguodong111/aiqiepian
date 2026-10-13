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
 * 风格参考 B站 DOTA2 UP主「三蛆」：文学梗/戏剧化/冒号金句/叙事体，标题必带主播名字。
 * 比赛切片：直播间名 +【战队1】VS【战队2】+ 时刻后缀；
 * 主播日常切片：本地大模型按三蛆风格生成，失败回退规则模板。
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

    /**
     * 三蛆风格标题模板（规则回退用）。
     * {anchor} = 主播精简名, {action} = 动作/事件, {detail} = 细节
     */
    private static final String[] SANQU_TEMPLATES = {
            "多年以后，{anchor}回忆起{action}的那一幕",
            "十步杀一人，{anchor}{action}全场看呆",
            "恐惧将至，{anchor}{action}一波带走",
            "天上{anchor}在天上飞，地上对手在地上追",
            "{anchor}：{action}，这波直接封神",
            "看着{anchor}{action}，弹幕全疯了",
            "{anchor}{action}，对面直接破防",
            "逆风翻盘！{anchor}{action}全场沸腾",
            "{anchor}{action}，这才是真正的操作",
            "当{anchor}开始{action}，比赛已经结束了",
    };

    /** 动作词池（按分数/场景选） */
    private static final String[] HIGH_ACTIONS = {"天秀操作秀翻全场", "1打5逆风翻盘", "神级走位秀麻了", "极限反杀看呆观众"};
    private static final String[] MID_ACTIONS = {"精彩操作引爆弹幕", "高能瞬间全场沸腾", "关键一波直接起飞"};
    private static final String[] LOW_ACTIONS = {"有趣瞬间笑麻了", "名场面来了", "精彩对决不容错过"};

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
        // 非比赛切片：优先用本地大模型按三蛆风格写标题
        if (allowLlm && llm.available()) {
            String ai = catchyTitle(clip, liveTitle);
            if (ai != null && !ai.isBlank()) {
                // 后处理：确保标题包含主播名字
                ai = ensureAnchorInTitle(ai, clip, liveTitle);
                return new Result(clamp(ai), "ai", "本地大模型三蛆风格生成");
            }
        }
        // 规则回退：三蛆风格模板
        String anchor = shortAnchor(clip.getAnchorName(), liveTitle);
        String sentence = danmakuSentence(topDanmakuWords(clip.getTopDanmaku(), 5));
        if (sentence != null) {
            String action = pickAction(clip);
            String title = applyTemplate(anchor, action, sentence);
            return new Result(clamp(title), "daily", "三蛆风格规则生成");
        }
        String hook = momentSuffix(clip);
        String fallback = anchor + " " + hook + "名场面";
        return new Result(clamp(fallback), "generic", "未采集到弹幕统计，使用通用模板");
    }

    /**
     * 本地大模型三蛆风格标题。
     * 喂干净的语境（主播名 + 精彩度 + 弹幕热词），prompt 里给三蛆标题范例，要求必带主播名字。
     */
    private String catchyTitle(Clip clip, String liveTitle) {
        List<String> words = topDanmakuWords(clip.getTopDanmaku(), 8);
        int score = clip.getScore() == null ? 0 : clip.getScore();
        String mood = score >= 90 ? "天秀高光" : score >= 80 ? "精彩操作" : score >= 70 ? "高能瞬间" : "看点片段";
        String anchor = shortAnchor(clip.getAnchorName(), liveTitle);
        String sys = "你是B站直播切片标题写手，风格参考DOTA2知名UP主「三蛆」。\n"
                + "三蛆标题特点：\n"
                + "1. 文学/影视梗开头：如「多年以后，AME在一千名的局...」「十步杀一人，AME35分钟18杀...」「恐惧将至，AME恐怖利刃...」\n"
                + "2. 戏剧化场景：如「天上猪哥在天上飞，地上森哥在地...」\n"
                + "3. 冒号+金句：如「查理斯：毛哥要是英语好一点去雪碧，雪碧真化龙了」「超哥：等你线下见到我就知道何为帅」\n"
                + "4. 叙事体：如「看着阿雅和乡下亲戚欢声笑语，城里...」「阿杨chalice团战平均生存时长5秒钟的...」\n"
                + "5. 标题必须包含主播名字（如AME/森哥/查理斯/猪哥/xinq/超哥等）\n"
                + "6. 口语化、有情绪或悬念，贴合游戏主题\n"
                + "7. 不要出现响度、能量、场景、帧、密度、弹幕、评分、精彩度等技术词\n"
                + "8. 不要书名号、引号、日期、编号、话题标签\n"
                + "9. 只输出标题本身（不超过20字），不要解释。";
        StringBuilder u = new StringBuilder();
        u.append("主播名字：").append(anchor).append("（标题必须包含此名字）\n");
        u.append("片段类型：").append(mood).append('\n');
        if (!words.isEmpty()) u.append("观众常刷的词：").append(String.join("、", words)).append('\n');
        return llm.oneLine(sys, u.toString(), 60, 30, 20);
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

    /** 后处理：如果 LLM 生成的标题不包含主播名字，强制插入 */
    private static String ensureAnchorInTitle(String title, Clip clip, String liveTitle) {
        String anchor = shortAnchor(clip.getAnchorName(), liveTitle);
        if (anchor.isEmpty() || "主播".equals(anchor)) return title;
        // 检查标题是否已包含主播名（不区分大小写）
        if (title.contains(anchor)) return title;
        // 尝试在标题开头或冒号后插入主播名
        if (title.contains("：") || title.contains(":")) {
            // 冒号前插入：如 "查理斯：xxx" 格式
            int idx = title.indexOf('：') > 0 ? title.indexOf('：') : title.indexOf(':');
            String before = title.substring(0, idx).trim();
            String after = title.substring(idx);
            if (before.isEmpty()) {
                return anchor + after;
            }
            return before + "，" + anchor + after;
        }
        // 在标题开头插入
        return anchor + " " + title;
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

    /** 根据分数选动作词 */
    private static String pickAction(Clip clip) {
        int score = clip.getScore() == null ? 0 : clip.getScore();
        String[] pool = score >= 90 ? HIGH_ACTIONS : score >= 80 ? MID_ACTIONS : LOW_ACTIONS;
        // 用 score 的个位数做伪随机选择，保证同一分数段稳定
        int idx = Math.abs(score) % pool.length;
        return pool[idx];
    }

    /** 应用三蛆风格模板 */
    private static String applyTemplate(String anchor, String action, String detail) {
        // 选模板：用 detail 长度做伪随机
        int idx = (detail == null ? 0 : detail.length()) % SANQU_TEMPLATES.length;
        String tpl = SANQU_TEMPLATES[idx];
        return tpl.replace("{anchor}", anchor)
                  .replace("{action}", action)
                  .replace("{detail}", detail != null ? detail : "");
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
