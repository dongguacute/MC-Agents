package com.mcagents.input;

import java.util.regex.Pattern;

/**
 * 常量集中定义，供 {@link Agent} 与各包内辅助类使用。
 */
final class AgentConstants {
    static final String AGENT_DISPLAY_NAME = "MCAGENT";
    /** 与 Fabric 惯例一致：模组配置与数据放在服务端根目录下的 {@code config/} 内。 */
    static final String CONFIG_DIR_NAME = "config";
    static final String DATA_DIR_NAME = "macagent";
    static final String RECORD_FILE_NAME = "agent_records.json";
    static final String CONFIG_FILE_NAME = "macagent.txt";
    static final String CONTROL_BOT_PROMPT_RELATIVE_PATH = "src/main/java/com/mcagents/prompt/ControlBot.md";
    static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    static final String DEFAULT_MODEL = "gpt-4o-mini";
    static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";
    static final int DEFAULT_MAX_CONTEXT_TOKENS = 0;
    static final int MIN_CONTEXT_TOKENS = 512;
    static final int MESSAGE_OVERHEAD_TOKENS = 4;
    static final Pattern CONTROL_DIRECTIVE_LINE_PATTERN = Pattern.compile("(?im)^\\s*\\[CONTROL_BOT\\].*$");
    static final Pattern CONTROL_DIRECTIVE_PRESENT_PATTERN = Pattern.compile("(?is)\\[CONTROL_BOT\\]\\s*(join|leave)\\s+");
    /** AI 在回复末尾声明需检索 Wiki 的指令行，例如：[MC_WIKI_SEARCH] ender dragon */
    static final Pattern WIKI_SEARCH_DIRECTIVE_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*\\[MC_WIKI_SEARCH\\]\\s+(.+)$"
    );
    /** 资料类 ask 第一轮：模型仅输出一行 WIKI_QUERY: … 供 MediaWiki 搜索 */
    static final Pattern WIKI_QUERY_LINE_PATTERN = Pattern.compile("(?i)^WIKI_QUERY:\\s*(.+)$");
    static final int MAX_WIKI_SEARCH_QUERY_CHARS = 240;
    /** 单轮回复中最多跟进的 [MC_WIKI_SEARCH] 次数（避免滥用 API / Wiki） */
    static final int MAX_WIKI_FOLLOWUP_QUERIES_PER_REPLY = 3;
    static final int MAX_WIKI_QUERY_DISPLAY_CHARS = 96;
    static final String WIKI_KEYWORD_EXTRACTION_SYSTEM = """
            You help search Minecraft Wiki (minecraft.wiki / zh.minecraft.wiki) via MediaWiki.
            First read the user's message, identify the main topic, then output the best 2–10 word search terms that match likely article titles.
            Output EXACTLY one line. No quotes, no markdown, no explanation, no other text.
            Format: WIKI_QUERY: <English or Chinese terms, e.g. Ender Dragon, 铁傀儡, redstone repeater>
            If the user mixes topics, pick the main one.""";
    static final Pattern PROMPT_LEAK_PATTERN = Pattern.compile(
            "(?is)(你是\\s*minecraft\\s*服务器助手|当且仅当用户明确要求控制假人上下线时|\\[control_bot\\]|system\\s*prompt|developer\\s*message|提示词|instruction)"
    );
    /** 单条 ask 中拆成多个子任务的连接词/分隔符（多行、分号、加号等） */
    static final Pattern COMPOUND_ASK_SPLIT = Pattern.compile(
            "\\s*(?:然后|接着|其次|另外|还有|并且|以及|顺带|顺便|之后|；|;)\\s*|\\s*\\+\\s*"
    );
    static final int COMPOUND_ASK_MIN_SEGMENT_LEN = 4;

    private AgentConstants() {
    }
}
