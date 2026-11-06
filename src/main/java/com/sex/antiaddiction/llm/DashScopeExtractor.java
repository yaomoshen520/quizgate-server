package com.sex.antiaddiction.llm;

import com.quizgate.proto.QuestionDraft;
import com.quizgate.proto.Subject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通义千问多模态（DashScope 原生接口）那一路识别引擎。
 * <p>
 * 三条刻意的取舍：
 * 一是不引第三方 SDK，只用 JDK 自带的 HttpClient + {@link MiniJson}——多一个依赖就多一处
 * native-image 的反射面，而这条路径默认不出网、冒烟脚本照不到它，出问题只会以"原生二进制一收请求就炸"的形式出现。
 * 二是原图 base64 之后只存在于这一个方法栈里，不入库也不落盘；库里只有识别出来的文本。
 * 三是口令只从环境变量读，代码和配置文件里没有一个默认口令。
 * <p>
 * 域名用的是文档里"现有域名仍可正常使用"那一个（dashscope.aliyuncs.com）。阿里云正在把它迁到
 * "{WorkspaceId}.cn-beijing.maas.aliyuncs.com" 的形状，所以端点整体做成可覆盖的配置，
 * 撞上新形状时改环境变量即可，不用改代码。
 */
@Component
public class DashScopeExtractor implements QuestionExtractor {

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

    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxDrafts;
    private final Duration timeout;
    private final HttpClient http;

    public DashScopeExtractor(
            @Value("${quizgate.extractor.enabled:false}") boolean enabled,
            @Value("${quizgate.extractor.endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/"
                    + "multimodal-generation/generation}") String endpoint,
            @Value("${quizgate.extractor.api-key:}") String apiKey,
            @Value("${quizgate.extractor.model:qwen-vl-max-latest}") String model,
            @Value("${quizgate.extractor.max-drafts:20}") int maxDrafts,
            @Value("${quizgate.extractor.timeout-ms:30000}") int timeoutMs) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.maxDrafts = maxDrafts;
        this.timeout = Duration.ofMillis(Math.max(1_000, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public List<QuestionDraft> extract(byte[] image, String mime) {
        if (!enabled) {
            throw new ExtractFailed("识别引擎还没启用：把 quizgate.extractor.enabled 设成 true 再试。");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExtractFailed("没读到识别引擎的口令：请把 DASHSCOPE_API_KEY 放进服务进程的环境变量里。");
        }
        if (image == null || image.length == 0) {
            throw new ExtractFailed("没收到图片。");
        }
        String body = request(image, mime);
        String raw = send(body);
        return draftsFrom(raw);
    }

    /**
     * 请求体手工拼：结构是死的，字段全是服务端自己控制的（模型名、base64、提示词），
     * 引掉 Jackson 的 POJO 绑定就少一处 native 反射面。字符串一律走 {@link MiniJson#quote}。
     */
    private String request(byte[] image, String mime) {
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
        return "{\"model\":" + MiniJson.quote(model)
                + ",\"input\":{\"messages\":["
                + "{\"role\":\"system\",\"content\":[{\"text\":"
                + MiniJson.quote("你是帮家长把卷子整理成题目的助手，严格遵守输出格式。") + "}]},{"
                + "\"role\":\"user\",\"content\":["
                + "{\"image\":" + MiniJson.quote(dataUrl) + "},"
                + "{\"text\":" + MiniJson.quote(PROMPT.formatted(maxDrafts)) + "}]}]},"
                + "\"parameters\":{\"result_format\":\"message\",\"temperature\":0}}";
    }

    private String send(String body) {
        var request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ExtractFailed("识别引擎返回 " + response.statusCode() + "：" + describeError(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new ExtractFailed("连不上识别引擎：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractFailed("识别被中断了，请重试。");
        }
    }

    /**
     * 从应答里取模型写的那段文本。
     * <p>
     * content 有两种合法形状：result_format=message 下是 [{"text":...}]，有的实现直接给字符串。
     * 两种都收，读不出来就老实说读不出来，不要在这里猜。
     */
    private List<QuestionDraft> draftsFrom(String raw) {
        MiniJson.Node root;
        try {
            root = MiniJson.parse(raw);
        } catch (MiniJson.SyntaxException e) {
            throw new ExtractFailed("识别引擎的应答不是 JSON：" + shorten(raw));
        }
        String text = contentText(root.get("output").get("choices").at(0).get("message").get("content"));
        if (text.isBlank()) {
            throw new ExtractFailed("识别引擎没回任何文字：" + describeError(raw));
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

    private static String contentText(MiniJson.Node content) {
        if (content instanceof MiniJson.Str) {
            return content.text();
        }
        var sb = new StringBuilder();
        for (var part : content.items()) {
            sb.append(part.get("text").text());
        }
        return sb.toString();
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

    /** 引擎错误体里的 code/message/request_id 拼成一句人话；request_id 留着，找阿里云报障要用。 */
    private static String describeError(String body) {
        try {
            var root = MiniJson.parse(body);
            var message = root.get("message").text();
            var code = root.get("code").text();
            var id = root.get("request_id").text();
            var sb = new StringBuilder();
            if (!code.isBlank()) {
                sb.append(code).append(' ');
            }
            sb.append(message);
            if (!id.isBlank()) {
                sb.append("（请求号 ").append(id).append("）");
            }
            return sb.toString().isBlank() ? shorten(body) : sb.toString();
        } catch (RuntimeException e) {
            return shorten(body);
        }
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
