package com.sex.antiaddiction.llm;

import com.quizgate.proto.QuestionDraft;
import com.quizgate.proto.Subject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * 识别引擎：DeepSeek flash，经 Spring AI 的 ChatModel 走 OpenAI 兼容协议。
 * <p>
 * 三条刻意的取舍：
 * 一是原图 base64 之后只存在于这一次调用栈里，不入库也不落盘；库里只有识别出来的文本。
 * 二是口令只从环境变量读，代码和配置文件里没有一个真口令；yml 里那个 not-configured
 * 是占位，因为 spring-ai 的连接属性要求非空才能建 bean，而服务在没配口令时也必须起得来。
 * 三是模型名、题量、token 预算全部是配置项，换供应商只改环境，不动这段代码。
 * <p>
 * 为什么用 flash 不用 v4-pro：识图这条路只有 flash 支持图像输入，pro 是纯文本模型。
 * 为什么 max-tokens 要给到四千：flash 是推理模型，预算的大半会被它自己的思考过程吃掉，
 * 给小了接口照样返回 200、但正文字段是空的，看上去就像"模型什么也没看出来"。
 */
@Component
public class DeepSeekExtractor implements QuestionExtractor {

    private static final String PROMPT = """
            识别这张卷子图片里的题目，只输出一个 JSON 数组，数组元素形如：
            {"subject":"MATH","grade":0,"knowledge_point":"20字以内的知识点","stem":"题干原文",\
            "options":["选项原文","选项原文"],"correct_index":0,"explanation":"一句话解析"}
            规则：
            1. 只收图片里确实存在的题，按出现顺序，最多 %d 道。
            2. stem 必须照抄原文，数字和运算符一个都不能改；不要缩写应用题。
            3. options 给 2 到 4 个，correct_index 指向你认为正确的那一项。拿不准也填你最支持的那一个，\
            服务端会自己重算一遍对答案。
            4. subject 只能是 MATH、CHINESE、ENGLISH 之一。grade 一律填 0，年级由系统按这个孩子定。
            5. 不要输出 markdown 代码块，不要输出数组以外的任何文字。
            """;

    private static final String SYSTEM = "你是帮家长把卷子整理成题目的助手，严格遵守输出格式。";

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final int maxDrafts;
    private final int maxTokens;
    private final Duration timeout;
    private final ChatModel chat;

    public DeepSeekExtractor(
            ChatModel chat,
            @Value("${quizgate.extractor.enabled:false}") boolean enabled,
            @Value("${quizgate.extractor.api-key:}") String apiKey,
            @Value("${quizgate.extractor.model:deepseek-flash}") String model,
            @Value("${quizgate.extractor.max-drafts:20}") int maxDrafts,
            @Value("${quizgate.extractor.max-tokens:4000}") int maxTokens,
            @Value("${quizgate.extractor.timeout-ms:30000}") int timeoutMs) {
        this.chat = chat;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.maxDrafts = maxDrafts;
        this.maxTokens = Math.max(256, maxTokens);
        this.timeout = Duration.ofMillis(Math.max(1_000, timeoutMs));
    }

    @Override
    public List<QuestionDraft> extract(byte[] image, String mime) {
        // 提示词只指环境变量，不指属性名：交付形态是原生二进制，application.yml 烘在可执行文件里
        // 改不动，家长能拧的只有服务进程的环境。
        if (!enabled) {
            throw new ExtractFailed("这台服务器还没打开识别功能："
                    + "在服务进程的环境里设 QUIZGATE_EXTRACTOR_ENABLED=true，配好 DEEPSEEK_API_KEY，重启服务就能用。");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExtractFailed("没读到识别引擎的口令：请把 DEEPSEEK_API_KEY 放进服务进程的环境变量里。");
        }
        if (image == null || image.length == 0) {
            throw new ExtractFailed("没收到图片。");
        }
        var user = UserMessage.builder()
                .text(PROMPT.formatted(maxDrafts))
                .media(new Media(MimeType.valueOf(mime), new ByteArrayResource(image)))
                .build();
        var options = OpenAiChatOptions.builder();
        options.model(model);
        options.maxTokens(maxTokens);
        options.timeout(timeout);
        // 识图是照抄，不是推理，所以把思考关掉。这不是省钱的顺手动作，是实测逼出来的：
        // flash 默认开着思考，一张整页卷子实测把 4000 个 token 全花在 reasoning 上
        // （reasoning_tokens=4000、finish_reason=length、正文字段是空串），家长只看到"没回任何文字"；
        // 同一张图关掉思考后 8.8 秒返回完整 JSON（原先 24 秒返回空）。
        options.extraBody(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        return draftsFrom(call(new Prompt(List.of(new SystemMessage(SYSTEM), user), options.build())));
    }

    /**
     * Spring AI 抛出来的异常种类很杂（超时、4xx、应答解析失败都各有其类），
     * 对家长有用的只有"哪一步没成"这一件事，所以统一收成一类人话，原始信息截短附上。
     */
    private String call(Prompt prompt) {
        try {
            var response = chat.call(prompt);
            var generation = response.getResult();
            return generation == null || generation.getOutput() == null ? "" : generation.getOutput().getText();
        } catch (ExtractFailed e) {
            throw e;
        } catch (RuntimeException e) {
            var why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ExtractFailed("识别引擎没答上话：" + shorten(why));
        }
    }

    private List<QuestionDraft> draftsFrom(String text) {
        if (text == null || text.isBlank()) {
            throw new ExtractFailed("识别引擎回了一段空正文。多半是 token 预算不够，"
                    + "把 quizgate.extractor.max-tokens 调大（当前 " + maxTokens + "）再试。");
        }
        MiniJson.Node block;
        try {
            block = MiniJson.parse(MiniJson.extractBlock(text));
        } catch (MiniJson.SyntaxException e) {
            throw new ExtractFailed("模型回的不是约定的 JSON 数组：" + shorten(text));
        }
        // 有的模型会把数组再包一层 {"questions":[...]}，收下来比退回给家长更有用。
        List<MiniJson.Node> items = block.items().isEmpty() ? block.get("questions").items() : block.items();
        if (items.isEmpty()) {
            throw new ExtractFailed("图片里一道题都没认出来，换一张清楚一点的重拍。");
        }
        // 提示词里已经说了最多几道，这里再硬性截一次：模型不守规矩时不能变成一次往库里塞两百道题的机会。
        if (items.size() > maxDrafts) {
            items = items.subList(0, maxDrafts);
        }
        var drafts = new ArrayList<QuestionDraft>();
        for (var item : items) {
            drafts.add(toDraft(item));
        }
        return drafts;
    }

    private static QuestionDraft toDraft(MiniJson.Node item) {
        var builder = QuestionDraft.newBuilder()
                .setSubject(subject(item.get("subject").text()))
                .setGrade(item.get("grade").intValue(0))
                .setKnowledgePoint(item.get("knowledge_point").text().trim())
                .setStem(item.get("stem").text().trim())
                .setCorrectIndex(item.get("correct_index").intValue(-1))
                .setExplanation(item.get("explanation").text().trim());
        for (var option : item.get("options").items()) {
            // 选项允许写成纯字符串，也允许写成 {"text":"..."}——两种都见过，多这一步比丢掉整道题便宜。
            String text = option instanceof MiniJson.Str ? option.text() : option.get("text").text();
            if (!text.isBlank()) {
                builder.addOptions(text.trim());
            }
        }
        return builder.build();
    }

    /** 模型爱用中文标签回学科，映射一遍比让它在字段校验里被判"学科未指定"有用。 */
    private static Subject subject(String value) {
        String text = value == null ? "" : value.trim().toUpperCase();
        return switch (text) {
            case "MATH", "数学" -> Subject.MATH;
            case "CHINESE", "语文" -> Subject.CHINESE;
            case "ENGLISH", "英语" -> Subject.ENGLISH;
            default -> Subject.SUBJECT_UNSPECIFIED;
        };
    }

    /** 回给家长的说明要短：模型原文可能整页都是字，而且里面可能夹着别人家的卷子内容。 */
    private static String shorten(String text) {
        if (text == null) {
            return "（空）";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
